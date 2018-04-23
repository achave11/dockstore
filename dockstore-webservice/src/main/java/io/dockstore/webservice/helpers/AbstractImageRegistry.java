/*
 *    Copyright 2017 OICR
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.dockstore.webservice.helpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.dockstore.client.cli.nested.AbstractEntryClient;
import io.dockstore.common.Registry;
import io.dockstore.webservice.core.Entry;
import io.dockstore.webservice.core.SourceFile;
import io.dockstore.webservice.core.Tag;
import io.dockstore.webservice.core.Token;
import io.dockstore.webservice.core.Tool;
import io.dockstore.webservice.core.ToolMode;
import io.dockstore.webservice.core.User;
import io.dockstore.webservice.jdbi.FileDAO;
import io.dockstore.webservice.jdbi.TagDAO;
import io.dockstore.webservice.jdbi.ToolDAO;
import io.dockstore.webservice.jdbi.UserDAO;
import org.apache.http.client.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract class for registries of docker containers.
 * *
 *
 * @author dyuen
 */
public abstract class AbstractImageRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractImageRegistry.class);

    /**
     * Get the list of namespaces and organizations that the user is associated to on Quay.io.
     *
     * @return list of namespaces
     */
    public abstract List<String> getNamespaces();

    /**
     * Get all tags for a given tool
     *
     * @return a list of tags for image that this points to
     */
    public abstract List<Tag> getTags(Tool tool);

    /**
     * Get all containers from provided namespaces
     *
     * @param namespaces
     * @return
     */
    public abstract List<Tool> getToolsFromNamespace(List<String> namespaces);

    /**
     * Updates each tool with build/general information
     *
     * @param apiTools
     */
    public abstract void updateAPIToolsWithBuildInformation(List<Tool> apiTools);

    /**
     * Returns the registry associated with the current class
     *
     * @return registry associated with class
     */
    public abstract Registry getRegistry();

    /**
     * Returns true if a tool can be converted to auto, false otherwise
     * @param tool
     * @return
     */
    public abstract boolean canConvertToAuto(Tool tool);

    /**
     * Updates/Adds/Deletes tools and their associated tags
     *
     * @param userId         The ID of the user
     * @param userDAO        ...
     * @param toolDAO        ...
     * @param tagDAO         ...
     * @param fileDAO        ...
     * @param client         An HttpClient used by source code repositories
     * @param githubToken    The user's GitHub token
     * @param bitbucketToken The user's Bitbucket token
     * @param gitlabToken    The user's GitLab token
     * @param organization   If not null, only refresh tools belonging to the specific organization. Otherwise, refresh all.
     * @return The list of tools that have been updated
     */
    @SuppressWarnings("checkstyle:parameternumber")
    public List<Tool> refreshTools(final long userId, final UserDAO userDAO, final ToolDAO toolDAO, final TagDAO tagDAO,
            final FileDAO fileDAO, final HttpClient client, final Token githubToken, final Token bitbucketToken, final Token gitlabToken,
            String organization) {
        // Get all the namespaces for the given registry
        List<String> namespaces;
        if (organization != null) {
            namespaces = Arrays.asList(organization);
        } else {
            namespaces = getNamespaces();
        }

        // Get all the tools based on the found namespaces
        List<Tool> apiTools = getToolsFromNamespace(namespaces);

        // Add manual tools to list of api tools
        User user = userDAO.findById(userId);
        List<Tool> manualTools = toolDAO.findByMode(ToolMode.MANUAL_IMAGE_PATH);

        // Get all tools in the db for the given registry
        List<Tool> dbTools = new ArrayList<>(getToolsFromUser(userId, userDAO));

        // Filter DB tools and API tools to only include relevant tools
        manualTools.removeIf(test -> !test.getUsers().contains(user) || !test.getRegistry().equals(getRegistry()));

        dbTools.removeIf(test -> !test.getRegistry().equals(getRegistry()));
        apiTools.addAll(manualTools);

        // Remove tools that can't be updated (Manual tools)
        dbTools.removeIf(tool1 -> tool1.getMode() == ToolMode.MANUAL_IMAGE_PATH);
        apiTools.removeIf(tool -> !namespaces.contains(tool.getNamespace()));
        dbTools.removeIf(tool -> !namespaces.contains(tool.getNamespace()));

        // Update api tools with build information
        updateAPIToolsWithBuildInformation(apiTools);

        // Update db tools by copying over from api tools
        List<Tool> newDBTools = updateTools(apiTools, dbTools, user, toolDAO);

        // Get tags and update for each tool
        for (Tool tool : newDBTools) {
            List<Tag> toolTags = getTags(tool);
            updateTags(toolTags, tool, githubToken, bitbucketToken, gitlabToken, tagDAO, fileDAO, toolDAO, client);
        }

        return newDBTools;
    }

    /**
     * Updates/Adds/Deletes a tool and the associated tags
     *
     * @return
     */
    @SuppressWarnings("checkstyle:parameternumber")
    Tool refreshTool(final long toolId, final Long userId, final UserDAO userDAO, final ToolDAO toolDAO, final TagDAO tagDAO,
            final FileDAO fileDAO, final HttpClient client, final Token githubToken, final Token bitbucketToken, final Token gitlabToken) {

        // Find tool of interest and store in a List (Allows for reuse of code)
        Tool tool = toolDAO.findById(toolId);
        List<Tool> apiTools = new ArrayList<>();

        // Convert the manual tool to automatic if possible
        // TODO: It may be possible that only the second conversion is required
        // Look for an automatic tool with the same path
        Optional<Tool> duplicatePathTool = toolDAO.findAllByPath(tool.getPath(), false)
                .stream()
                .filter(t -> t.getMode() != ToolMode.MANUAL_IMAGE_PATH)
                .findFirst();

        // If exists, check conditions to see if it should be changed to auto (in sync with quay tags and git repo)
        if (tool.getMode() == ToolMode.MANUAL_IMAGE_PATH && duplicatePathTool.isPresent() && tool.getRegistry()
                .equals(Registry.QUAY_IO.toString()) && duplicatePathTool.get().getGitUrl().equals(tool.getGitUrl())) {
            tool.setMode(duplicatePathTool.get().getMode());
        }

        // Check if manual Quay repository can be changed to automatic
        if (tool.getMode() == ToolMode.MANUAL_IMAGE_PATH && tool.getRegistry().equals(Registry.QUAY_IO.toString())) {
            if (canConvertToAuto(tool)) {
                tool.setMode(ToolMode.AUTO_DETECT_QUAY_TAGS_AUTOMATED_BUILDS);
            }
        }

        // Get tool information from API (if not manual) and remove from api list all tools besides the tool of interest
        if (tool.getMode() == ToolMode.MANUAL_IMAGE_PATH) {
            apiTools.add(tool);
        } else {
            List<String> namespaces = new ArrayList<>();
            namespaces.add(tool.getNamespace());
            apiTools.addAll(getToolsFromNamespace(namespaces));
        }

        apiTools.removeIf(container1 -> !container1.getPath().equals(tool.getPath()));

        // Update api tools with build information
        updateAPIToolsWithBuildInformation(apiTools);

        // List of db tools should just include the tool you are refreshing (since it must exist in the database)
        List<Tool> dbTools = new ArrayList<>();
        dbTools.add(tool);
        dbTools.removeIf(tool1 -> tool1.getMode() == ToolMode.MANUAL_IMAGE_PATH);

        // Update db tools by copying over from api tools
        final User user = userDAO.findById(userId);
        updateTools(apiTools, dbTools, user, toolDAO);

        // Grab updated tool from the database
        final List<Tool> newDBTools = new ArrayList<>();
        newDBTools.add(toolDAO.findById(tool.getId()));

        // Get tags and update for each tool (including manual tools)
        List<Tag> toolTags = getTags(tool);
        updateTags(toolTags, tool, githubToken, bitbucketToken, gitlabToken, tagDAO, fileDAO, toolDAO, client);

        // Return the updated tool (first array element)
        return newDBTools.get(0);
    }

    /**
     * Updates/Adds/Deletes tags for a specific tool
     *
     * @param newTags
     * @param tool
     * @param githubToken
     * @param bitbucketToken
     * @param tagDAO
     * @param fileDAO
     * @param toolDAO
     * @param client
     */
    @SuppressWarnings("checkstyle:parameternumber")
    public void updateTags(List<Tag> newTags, Tool tool, Token githubToken, Token bitbucketToken, Token gitlabToken, final TagDAO tagDAO,
            final FileDAO fileDAO, final ToolDAO toolDAO, final HttpClient client) {
        // Get all existing tags
        List<Tag> existingTags = new ArrayList<>(tool.getTags());

        // If automatic tool or a Quay tool with no tags
        if (tool.getMode() != ToolMode.MANUAL_IMAGE_PATH || (tool.getRegistry().equals(Registry.QUAY_IO.toString()) && existingTags.isEmpty())) {

            if (newTags == null) {
                LOG.info(githubToken.getUsername() + " : Tags for tool {} did not get updated because new tags were not found",
                        tool.getPath());
                return;
            }

            // Find all tags that exist in DB but not from the API, prepare to delete
            List<Tag> toDelete = new ArrayList<>();
            for (Iterator<Tag> iterator = existingTags.iterator(); iterator.hasNext(); ) {
                Tag oldTag = iterator.next();
                boolean exists = false;
                for (Tag newTag : newTags) {
                    if (newTag.getName().equals(oldTag.getName())) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    toDelete.add(oldTag);
                    iterator.remove();
                }
            }

            // Add or update tags
            for (Tag newTag : newTags) {
                boolean existsInDB = false;

                // If API tag already exists in the DB then update tag
                for (Tag oldTag : existingTags) {
                    if (newTag.getName().equals(oldTag.getName())) {
                        existsInDB = true;

                        oldTag.update(newTag);

                        // Update tag with default paths if dirty bit not set
                        if (!oldTag.isDirtyBit()) {
                            // Has not been modified => set paths
                            oldTag.setCwlPath(tool.getDefaultCwlPath());
                            oldTag.setWdlPath(tool.getDefaultWdlPath());
                            oldTag.setDockerfilePath(tool.getDefaultDockerfilePath());
                            if (tool.getDefaultTestCwlParameterFile() != null) {
                                oldTag.getSourceFiles().add(createSourceFile(tool.getDefaultTestCwlParameterFile(), SourceFile.FileType.CWL_TEST_JSON));
                            }
                            if (tool.getDefaultTestWdlParameterFile() != null) {
                                oldTag.getSourceFiles().add(createSourceFile(tool.getDefaultTestWdlParameterFile(), SourceFile.FileType.WDL_TEST_JSON));
                            }
                        }

                        break;
                    }
                }

                // If the API tag does not exist in the DB (new) then add tag
                if (!existsInDB) {
                    // this could result in the same tag being added to multiple tools with the same path, need to clone
                    Tag clonedTag = new Tag();
                    clonedTag.clone(newTag);
                    if (tool.getDefaultTestCwlParameterFile() != null) {
                        clonedTag.getSourceFiles().add(createSourceFile(tool.getDefaultTestCwlParameterFile(), SourceFile.FileType.CWL_TEST_JSON));
                    }
                    if (tool.getDefaultTestWdlParameterFile() != null) {
                        clonedTag.getSourceFiles().add(createSourceFile(tool.getDefaultTestWdlParameterFile(), SourceFile.FileType.WDL_TEST_JSON));
                    }
                    existingTags.add(clonedTag);
                }
            }

            // Create new tags from API that do not exist in the DB
            boolean allAutomated = true;
            for (Tag tag : existingTags) {
                if (!tool.getTags().contains(tag)) {
                    LOG.info(githubToken.getUsername() + " : UPDATING tag: {}", tag.getName());

                    long id = tagDAO.create(tag);
                    tag = tagDAO.findById(id);
                    tool.addTag(tag);

                    if (!tag.isAutomated()) {
                        allAutomated = false;
                    }
                }
            }

            // Delete tag if it no longer has a matching API tag
            for (Tag t : toDelete) {
                LOG.info(githubToken.getUsername() + " : DELETING tag: {}", t.getName());
                t.getSourceFiles().clear();
                tool.getTags().remove(t);
            }

            if (tool.getMode() != ToolMode.MANUAL_IMAGE_PATH) {
                if (allAutomated) {
                    tool.setMode(ToolMode.AUTO_DETECT_QUAY_TAGS_AUTOMATED_BUILDS);
                } else {
                    tool.setMode(ToolMode.AUTO_DETECT_QUAY_TAGS_WITH_MIXED);
                }
            }
        }

        // Grab files for each tag and check if valid
        Helper.updateFiles(tool, client, fileDAO, githubToken, bitbucketToken, gitlabToken);

        // Now grab default/main tag to grab general information (defaults to github/bitbucket "main branch")
        final SourceCodeRepoInterface sourceCodeRepo = SourceCodeRepoFactory
                .createSourceCodeRepo(tool.getGitUrl(), client, bitbucketToken == null ? null : bitbucketToken.getContent(),
                        gitlabToken == null ? null : gitlabToken.getContent(), githubToken.getContent());
        if (sourceCodeRepo != null) {
            // Grab and parse files to get tool information
            // Add for new descriptor types

            //Check if default version is set
            // If not set or invalid, set tag of interest to tag stored in main tag
            // If set and valid, set tag of interest to tag stored in default version

            if (tool.getDefaultCwlPath() != null) {
                LOG.info(githubToken.getUsername() + " : Parsing CWL...");
                sourceCodeRepo.updateEntryMetadata(tool, AbstractEntryClient.Type.CWL);
            }

            if (tool.getDefaultWdlPath() != null) {
                LOG.info(githubToken.getUsername() + " : Parsing WDL...");
                sourceCodeRepo.updateEntryMetadata(tool, AbstractEntryClient.Type.WDL);
            }
        }

        toolDAO.create(tool);
    }

    private SourceFile createSourceFile(String path, SourceFile.FileType type) {
        SourceFile sourcefile = new SourceFile();
        sourcefile.setPath(path);
        sourcefile.setType(type);
        return sourcefile;
    }

    /**
     * Gets tools for the current user
     *
     * @param userId
     * @param userDAO
     * @return
     */
    public List<Tool> getToolsFromUser(Long userId, UserDAO userDAO) {
        final Set<Entry> entries = userDAO.findById(userId).getEntries();
        List<Tool> toolList = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry instanceof Tool) {
                toolList.add((Tool)entry);
            }
        }

        return toolList;
    }

    /**
     * Updates the new list of tools to the database. Deletes tools that have no users.
     *
     * @param apiToolList tools retrieved from quay.io
     * @param dbToolList  tools retrieved from the database for the current user (not including manual tools)
     * @param user        the current user
     * @param toolDAO
     * @return list of newly updated containers
     */
    public List<Tool> updateTools(final Iterable<Tool> apiToolList, final List<Tool> dbToolList, final User user, final ToolDAO toolDAO) {

        // Find all of the existing DB Tools that no longer have a matching API Tool
        final List<Tool> toDelete = new ArrayList<>();

        for (final Iterator<Tool> iterator = dbToolList.iterator(); iterator.hasNext(); ) {
            final Tool toolFromDB = iterator.next();
            boolean existOnAPI = false;

            for (final Tool toolFromAPI : apiToolList) {
                if (toolFromAPI.getPath().equals(toolFromDB.getPath()) && toolFromAPI.getGitUrl().equals(toolFromDB.getGitUrl())) {
                    existOnAPI = true;
                    break;
                }
            }

            if (!existOnAPI) {
                toolFromDB.removeUser(user);
                toDelete.add(toolFromDB);
                iterator.remove();
            }
        }

        // Update existing tools or add new tools
        for (Tool toolFromAPI : apiToolList) {
            String path = toolFromAPI.getToolPath();
            boolean existsInDB = false;

            // Find if user already has the tool in the database, update some tool metadata
            for (Tool toolFromDB : dbToolList) {
                if (toolFromAPI.getPath().equals(toolFromDB.getPath()) && toolFromAPI.getGitUrl().equals(toolFromDB.getGitUrl())) {
                    existsInDB = true;
                    toolFromDB.update(toolFromAPI);
                    break;
                }
            }

            // Find if tool already exists, but does not belong to user
            if (!existsInDB) {
                Tool toolFromDB = toolDAO.findByPath(path, false);
                if (toolFromDB != null) {
                    existsInDB = true;
                    toolFromDB.update(toolFromAPI);
                    dbToolList.add(toolFromDB);
                }
            }

            // Add tool if it does not exist in the database
            if (!existsInDB) {
                dbToolList.add(toolFromAPI);
            }
        }

        // Save all new and existing tools
        for (final Tool tool : dbToolList) {
            tool.setLastUpdated(new Date());
            tool.addUser(user);
            toolDAO.create(tool);
            LOG.info(user.getUsername() + ": UPDATED Tool: {}", tool.getPath());
        }

        // Delete container if it has no users
        for (Tool c : toDelete) {
            LOG.info(user.getUsername() + ": {} {}", c.getPath(), c.getUsers().size());

            if (c.getUsers().isEmpty()) {
                LOG.info(user.getUsername() + ": DELETING Tool: {}", c.getPath());
                c.getTags().clear();
                toolDAO.delete(c);
            }
        }

        return dbToolList;
    }
}
