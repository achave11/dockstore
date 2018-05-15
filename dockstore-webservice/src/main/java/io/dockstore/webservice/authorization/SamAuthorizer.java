package io.dockstore.webservice.authorization;

import java.text.MessageFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.DockstoreWebserviceConfiguration;
import io.dockstore.webservice.core.Action;
import io.dockstore.webservice.core.Permission;
import io.dockstore.webservice.core.Role;
import io.dockstore.webservice.core.User;
import io.dockstore.webservice.core.Workflow;
import io.dockstore.webservice.jdbi.TokenDAO;
import io.swagger.sam.client.ApiClient;
import io.swagger.sam.client.ApiException;
import io.swagger.sam.client.JSON;
import io.swagger.sam.client.api.ResourcesApi;
import io.swagger.sam.client.model.AccessPolicyMembership;
import io.swagger.sam.client.model.AccessPolicyResponseEntry;
import io.swagger.sam.client.model.ErrorReport;
import org.apache.http.HttpStatus;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An implementation of the {@link AuthorizationInterface} that makes
 * calls to SAM.
 */
public class SamAuthorizer implements AuthorizationInterface {

    private static final String SAM_RESOURCE_TYPE = "tool";

    // Actions defined in SAM
    private static final String READ_ACTION = "read";
    private static final String WRITE_ACTION = "write";

    // Policies defined in SAM
    private static final String READ_POLICY = "reader";
    private static final String WRITE_POLICY = "writer";
    private static final String OWNER_POLICY = "owner";

    private static Map<String, Role> samPermissionMap = new HashMap<>();
    private static final Logger LOG = LoggerFactory.getLogger(SamAuthorizer.class);
    static {
        samPermissionMap.put("owner", Role.OWNER);
        samPermissionMap.put("writer", Role.WRITER);
        samPermissionMap.put("reader", Role.READER);
    }
    private static Map<Role, String> permissionSamMap =
            samPermissionMap.entrySet().stream().collect(Collectors.toMap(Map.Entry::getValue, c -> c.getKey()));

    private DockstoreWebserviceConfiguration config;
    private final TokenDAO tokenDAO;

    public SamAuthorizer(TokenDAO tokenDAO, DockstoreWebserviceConfiguration config) {
        this.tokenDAO = tokenDAO;
        this.config = config;
    }

    @Override
    public Optional<Role> getPermission(User requester, Workflow workflow) {
        return Optional.empty();
    }

    @Override
    public List<Permission> setPermission(Workflow workflow, User requester, Permission permission) {
        ResourcesApi resourcesApi = new ResourcesApi(getApiClient(requester));
        try {
            final String encodedPath = resourcesApi.getApiClient().escapeString(workflow.getWorkflowPath());

            ensureResourceExists(workflow, requester, resourcesApi, encodedPath);

            resourcesApi.addUserToPolicy(SAM_RESOURCE_TYPE,
                    encodedPath,
                    permissionSamMap.get(permission.getRole()),
                    permission.getEmail());
            return getPermissionsForWorkflow(requester, workflow);
        } catch (ApiException e) {
            String errorMessage = readValue(e, ErrorReport.class)
                    .map(errorReport -> errorReport.getMessage())
                    .orElse("Error setting permission");
            LOG.error(errorMessage, e);
            throw new CustomWebApplicationException(errorMessage, e.getCode());
        }
    }

    private void ensureResourceExists(Workflow workflow, User requester, ResourcesApi resourcesApi, String encodedPath) {
        try {
            resourcesApi.listResourcePolicies(SAM_RESOURCE_TYPE, encodedPath);
        } catch (ApiException e) {
            if (e.getCode() == HttpStatus.SC_NOT_FOUND) {
                initializePermission(workflow, requester);
            } else {
                throw new CustomWebApplicationException("Error listing permissions", e.getCode());
            }
        }
    }

    @Override
    public List<String> workflowsSharedWithUser(User user) {
        return null;
    }

    @Override
    public List<Permission> getPermissionsForWorkflow(User user, Workflow workflow) {
        ResourcesApi resourcesApi = new ResourcesApi(getApiClient(user));
        try {
            String encoded = resourcesApi.getApiClient().escapeString(workflow.getWorkflowPath());
            return accessPolicyResponseEntryToUserPermissions(resourcesApi.listResourcePolicies(SAM_RESOURCE_TYPE,
                    encoded));
        } catch (ApiException e) {
            // If 404, the SAM resource has not yet been created, so just return an empty list.
            if (e.getCode() != HttpStatus.SC_NOT_FOUND) {
                throw new CustomWebApplicationException("Error getting permissions", e.getCode());
            }
        }
        return Collections.emptyList();
    }

    @Override
    public void removePermission(Workflow workflow, User user, String email, Role role) {
        ResourcesApi resourcesApi = new ResourcesApi(getApiClient(user));
        String encodedPath = resourcesApi.getApiClient().escapeString(workflow.getWorkflowPath());
        try {
            List<AccessPolicyResponseEntry> entries = resourcesApi.listResourcePolicies(SAM_RESOURCE_TYPE, encodedPath);
            for (AccessPolicyResponseEntry entry : entries) {
                if (permissionSamMap.get(role).equals(entry.getPolicyName())) {
                    if (entry.getPolicy().getMemberEmails().contains(email)) {
                        resourcesApi.removeUserFromPolicy(SAM_RESOURCE_TYPE, encodedPath, entry.getPolicyName(), email);
                    }
                }
            }
        } catch (ApiException e) {
            LOG.error(MessageFormat.format("Error removing {0} from workflow {1}", email, encodedPath), e);
            throw new CustomWebApplicationException("Error removing permissions", e.getCode());
        }
    }

    @Override
    public void initializePermission(Workflow workflow, User user) {
        ResourcesApi resourcesApi = new ResourcesApi(getApiClient(user));
        String encodedPath = resourcesApi.getApiClient().escapeString(workflow.getWorkflowPath());
        try {
            resourcesApi.createResourceWithDefaults(SAM_RESOURCE_TYPE, encodedPath);

            final AccessPolicyMembership writerPolicy = new AccessPolicyMembership();
            writerPolicy.addRolesItem("writer");
            resourcesApi.overwritePolicy(SAM_RESOURCE_TYPE, encodedPath, "writer", writerPolicy);

            final AccessPolicyMembership readerPolicy = new AccessPolicyMembership();
            readerPolicy.addRolesItem("reader");
            resourcesApi.overwritePolicy(SAM_RESOURCE_TYPE, encodedPath, "reader", readerPolicy);
        } catch (ApiException e) {
            throw new CustomWebApplicationException("Error initializing permissions", e.getCode());
        }
    }

    @Override
    public boolean canRead(User user, Workflow workflow) {
        return canDoAction(user, workflow, Action.READ);
    }

    @Override
    public boolean canWrite(User user, Workflow workflow) {
        return canDoAction(user, workflow, Action.WRITE);
    }

    @Override
    public boolean canDelete(User user, Workflow workflow) {
        return canDoAction(user, workflow, Action.DELETE);
    }

    @Override
    public boolean isOwner(User user, Workflow workflow) {
        return false;
    }

    private boolean canDoAction(User user, Workflow workflow, Action action) {
        ResourcesApi resourcesApi = new ResourcesApi(getApiClient(user));
        String encodedPath = resourcesApi.getApiClient().escapeString(workflow.getWorkflowPath());
        try {
            return resourcesApi.resourceAction(SAM_RESOURCE_TYPE, encodedPath, action.toString());
        } catch (ApiException e) {
            return false;
        }
    }

    //    private void

    private ApiClient getApiClient(User user) {
        ApiClient apiClient = new ApiClient() {
            @Override
            protected void performAdditionalClientConfiguration(ClientConfig clientConfig) {
                // Calling ResourcesApi.addUserToPolicy invokes PUT with a body, which will fail
                // without this:
                clientConfig.property(ClientProperties.SUPPRESS_HTTP_COMPLIANCE_VALIDATION, true);
            }
        };
        apiClient.setBasePath(config.getSamConfiguration().getBasepath());
        // TODO: Get actual access token. May need to refresh the token
//        Token token = tokenDAO.findById(user.getId());
//        apiClient.setAccessToken(token.getToken());
        apiClient.setAccessToken(System.getProperty("accessToken")); // TODO: Get access token from user
        return apiClient;
    }

    static List<Permission> accessPolicyResponseEntryToUserPermissions(List<AccessPolicyResponseEntry> accessPolicyList) {
        return accessPolicyList.stream().map(accessPolicy -> {
            Role role = samPermissionMap.get(accessPolicy.getPolicy().getRoles().get(0));
            return accessPolicy.getPolicy().getMemberEmails().stream().map(email -> {
                Permission permission = new Permission();
                permission.setRole(role);
                permission.setEmail(email);
                return permission;
            });
        }).flatMap(s -> s).collect(Collectors.toList());
    }

    static <T> Optional<T> readValue(ApiException e, Class<T> clazz) {
        String body = e.getResponseBody();
        return readValue(body, clazz);
    }

    static <T> Optional<T> readValue(String body, Class<T> clazz) {
        try {
            ObjectMapper context = new JSON().getContext(clazz);
            return Optional.of(context.readValue(body, clazz));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }
}
