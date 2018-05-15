package io.dockstore.webservice.authorization;

import java.util.Arrays;
import java.util.Optional;

import io.dockstore.webservice.core.Role;
import io.dockstore.webservice.core.Permission;
import io.swagger.sam.client.model.AccessPolicyMembership;
import io.swagger.sam.client.model.AccessPolicyResponseEntry;
import io.swagger.sam.client.model.ErrorReport;
import org.hamcrest.CoreMatchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SamAuthorizerTest {

    private AccessPolicyResponseEntry ownerPolicy;
    private AccessPolicyResponseEntry writerPolicy;

    @Before
    public void setup() {
        ownerPolicy = new AccessPolicyResponseEntry();
        ownerPolicy.setPolicyName("owner");
        AccessPolicyMembership accessPolicyMembership = new AccessPolicyMembership();
        accessPolicyMembership.setRoles(Arrays.asList(new String[] {"owner"}));
        accessPolicyMembership.setMemberEmails(Arrays.asList(new String [] {"jdoe@ucsc.edu"}));
        ownerPolicy.setPolicy(accessPolicyMembership);

        writerPolicy = new AccessPolicyResponseEntry();
        writerPolicy.setPolicyName("writer");
        AccessPolicyMembership writerMembership = new AccessPolicyMembership();
        writerMembership.setRoles(Arrays.asList(new String[] {"writer"}));
        writerMembership.setMemberEmails(Arrays.asList(new String[] {"jane.doe@gmail.com"}));
        writerPolicy.setPolicy(writerMembership);
    }

    @Test
    public void testAccessPolicyResponseEntryToUserPermissions() {
        Permission ownerPermission = new Permission();
        ownerPermission.setEmail("jdoe@ucsc.edu");
        ownerPermission.setRole(Role.OWNER);
        Assert.assertThat(SamAuthorizer
                .accessPolicyResponseEntryToUserPermissions(Arrays.asList(ownerPolicy)),
                CoreMatchers.is(Arrays.asList(ownerPermission)));

        Permission writerPermission = new Permission();
        writerPermission.setEmail("jane.doe@gmail.com");
        writerPermission.setRole(Role.WRITER);

        Assert.assertThat(SamAuthorizer
            .accessPolicyResponseEntryToUserPermissions(Arrays.asList(ownerPolicy, writerPolicy)),
            CoreMatchers.is(Arrays.asList(new Permission[] {ownerPermission, writerPermission})));
    }

    @Test
    public void testReadValue() {
        String response = "{\n" +
                "\"statusCode\": 400,\n" +
                "\"source\": \"sam\",\n" +
                "\"causes\": [],\n" +
                "\"stackTrace\": [],\n" +
                "\"message\": \"jane_doe@yahoo.com not found\"\n" +
                "}";
        Optional<ErrorReport> errorReport = SamAuthorizer.readValue(response, ErrorReport.class);
        Assert.assertEquals(errorReport.get().getMessage(), "jane_doe@yahoo.com not found");

        Assert.assertFalse(SamAuthorizer.readValue((String)null, ErrorReport.class).isPresent());
    }

}
