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
 */package io.dockstore.webservice.authorization;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.DockstoreWebserviceConfiguration;
import io.dockstore.webservice.core.Entry;
import io.dockstore.webservice.core.Permission;
import io.dockstore.webservice.core.Role;
import io.dockstore.webservice.core.User;
import io.dockstore.webservice.core.Workflow;
import io.dockstore.webservice.core.WorkflowMode;
import org.apache.http.HttpStatus;

/**
 * Implementation of the <code>AuthorizationInterface</code> that only reads
 * and saves settings in memory. In other words, when you bring down the
 * Java VM, all settings are lost!
 *
 * <p>This is useful for testing without having any dependencies on an external
 * authorization service.</p>
 *
 * <p>Note that this authorizer implementation does not support user groups.</p>
 */
public class InMemoryAuthorizer implements AuthorizationInterface {

    private final Map<Long, Map<String, Role>> map = new ConcurrentHashMap<>();
    private DockstoreWebserviceConfiguration configuration;

    @Override
    public Optional<Role> getPermission(User requester, Workflow workflow) {
        final Map<String, Role> userPermissionsMap = map.get(workflow.getId());
        if (userPermissionsMap == null) {
            return Optional.empty();
        }
        return Optional.of(userPermissionsMap.get(requester.getEmail()));
    }

    @Override
    public List<Permission> setPermission(Workflow workflow, User requester, Permission permission) {
        Map<String, Role> entryMap = map.get(workflow.getId());
        if (entryMap == null) {
            entryMap = new ConcurrentHashMap<>();
            map.put(workflow.getId(), entryMap);
        }
        entryMap.put(permission.getEmail(), permission.getRole());
        return getPermissionsForWorkflow(requester, workflow);
    }

    @Override
    public List<String> workflowsSharedWithUser(User user) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Permission> getPermissionsForWorkflow(User user, Workflow workflow) {
        List<Permission> owners = getHostedOwners(workflow);
        Map<String, Role> permissionMap = map.get(workflow.getId());
        if (permissionMap == null) {
            return owners;
        }
        List<Permission> permissionList = permissionMap.entrySet().stream().map(e -> {
            Permission permission = new Permission();
            permission.setEmail(e.getKey());
            permission.setRole(e.getValue());
            return permission;
        }).collect(Collectors.toList());
        permissionList.addAll(owners);
        return permissionList;
    }

    private List<Permission> getHostedOwners(Entry entry) {
        List<Permission> list = new ArrayList<>();
        if (entry instanceof Workflow) {
            Workflow workflow = (Workflow)entry;
            if (workflow.getMode() == WorkflowMode.HOSTED) {
                Set<User> users = entry.getUsers();
                list = users.stream().map(u -> {
                    Permission permission = new Permission();
                    permission.setRole(Role.OWNER);
                    permission.setEmail(u.getEmail() != null ? u.getEmail() : u.getUsername());
                    return permission;
                }).collect(Collectors.toList());
            }
        }
        return list;
    }

    @Override
    public void removePermission(Workflow workflow, User user, String email, Role role) {
        Map<String, Role> userPermissionMap = map.get(workflow.getId());
        if (userPermissionMap != null) {
            if (role == Role.OWNER) {
                // Make sure not the last owner
                if (userPermissionMap.values().stream().filter(p -> p == Role.OWNER).collect(Collectors.toList()).size() < 2) {
                    throw new CustomWebApplicationException("The last owner cannot be removed", HttpStatus.SC_BAD_REQUEST);
                }
            }
            userPermissionMap.remove(email);
        }
    }

    @Override
    public void initializePermission(Workflow workflow, User user) {
        Optional<Role> permission = getPermission(user, workflow);
        if (permission.isPresent()) {
            throw new CustomWebApplicationException("Permissions already exist", HttpStatus.SC_BAD_REQUEST);
        }
        Permission userPermission = new Permission();
        userPermission.setEmail(user.getEmail());
        userPermission.setRole(Role.OWNER);
        setPermission(workflow, user, userPermission);
    }

    @Override
    public boolean canRead(User user, Workflow workflow) {
        // All permissions can read
        return getPermission(user, workflow).map(p -> true).orElse(false);
    }

    @Override
    public boolean canWrite(User user, Workflow workflow) {
        // Any permission but READER can write
        return getPermission(user, workflow).map(p -> p != Role.READER).orElse(false);
    }

    @Override
    public boolean isOwner(User user, Workflow workflow) {
        return getPermission(user, workflow).map(p -> p == Role.OWNER).orElse(false);
    }

    @Override
    public boolean canDelete(User user, Workflow workflow) {
        return canWrite(user, workflow);
    }

}

