/*
 *    Copyright 2018 OICR
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

package io.dockstore.webservice.authorization;

import java.util.List;
import java.util.Optional;

import io.dockstore.webservice.core.Permission;
import io.dockstore.webservice.core.Role;
import io.dockstore.webservice.core.User;
import io.dockstore.webservice.core.Workflow;

/**
 * <p>
 * Abstracts out the backend authorization service.
 * </p>
 * <p>
 * A <code>Permission</code> is an email address
 * and a  {@link Role},
 * an enum whose values are <code>OWNER</code>,
 * <code>WRITER</code>, and <code>READER</code>.
 * </p>
 * <p>
 * An email address can be for a single user or for a group.
 * </p>
 * <p>
 * An <code>Workflow</code> can have multiple <code>Permission</code>s associated
 * with it.
 * </p>
 */
public interface AuthorizationInterface {

    /**
     * Returns <code>requestor</code>'s permission for an workflow, if any
     * @param requester
     * @param workflow
     * @return the user's permission
     */
    Optional<Role> getPermission(User requester, Workflow workflow);

    /**
     * Adds or modifies a <code>Permission</code> to have the specified permissions on an workflow.
     *
     * <p>If the email in <code>permission</code> does not have any permissions on the workflow,
     * the email is added with the specified permission. If the email already has a permission,
     * then the permission is updated.</p>
     * @param workflow the workflow
     * @param requester -- the requester, who must be an owner of <code>workflow</code> or an admin
     * @param permission -- the email and the permission for that email
     * @return the list of the workflow's permissions after having added or modified
     */
    List<Permission> setPermission(Workflow workflow, User requester, Permission permission);

    /**
     * Returns the workflow paths of all entries that have been shared with the specified <code>user</code>.
     *
     * @param user
     * @return this list of all entries shared with the user
     */
    List<String> workflowsSharedWithUser(User user);

    /**
     * Lists all <code>Permission</code>s for <code>workflow</code>
     * @param user the user, who must either be an owner of the workflow or an admin
     * @param workflow the workflow
     * @return a list of users and their permissions
     */
    List<Permission> getPermissionsForWorkflow(User user, Workflow workflow);

    /**
     * Removes the <code>Permission</code> containing <code>email</code> from
     * <code>workflow</code>'s permissions.
     * @param workflow
     * @param user the requester, must be an owner of <code>workflow</code> or an admin.
     * @param email the email of the user to remove
     * @param role
     */
    void removePermission(Workflow workflow, User user, String email, Role role);

    /**
     * Initializes permissions for <code>workflow</code>, making <code>user</code> the owner.
     *
     * <p>
     *     Any authenticated user can make this call! It will fail if the permissions
     *     have already been initialized.
     * </p>
     *
     *
     * @param workflow
     * @param user
     */
    void initializePermission(Workflow workflow, User user);

    /**
     * Indicates whether <code>user</code> has permission to read <code>workflow</code>.
     * @param user
     * @param workflow
     * @return true if user can read workflow, false otherwise
     */
    boolean canRead(User user, Workflow workflow);

    /**
     * Specifies whether <code>user</code> has permission to write to <code>workflow</code>
     * @param user
     * @param workflow
     * @return true if user can write to workflow, false otherwise
     */
    boolean canWrite(User user, Workflow workflow);

    /**
     * Specifies whether <code>user</code> is an owner of <code>workflow</code>.
     * @param user
     * @param workflow
     * @return true if user is an owner of workflow, false otherwise
     */
    boolean isOwner(User user, Workflow workflow);

    /**
     * Specifies whether <code>user</code> can delete <code>workflow</code>.
     *
     * @param user
     * @param workflow
     * @return true if the user can delete the workflow, false otherwise.
     */
    boolean canDelete(User user, Workflow workflow);
}
