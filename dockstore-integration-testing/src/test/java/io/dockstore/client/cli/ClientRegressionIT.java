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

package io.dockstore.client.cli;

import java.io.File;
import java.io.IOException;
import java.net.URL;

import io.dockstore.common.CommonTestUtilities;
import io.dockstore.common.RegressionTest;
import io.dockstore.common.TestUtility;
import io.dropwizard.testing.ResourceHelpers;
import io.swagger.client.ApiException;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;
import org.junit.contrib.java.lang.system.SystemErrRule;
import org.junit.contrib.java.lang.system.SystemOutRule;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;

import static io.dockstore.common.CommonTestUtilities.OLD_DOCKSTORE_VERSION;
import static io.dockstore.common.CommonTestUtilities.checkToolList;
import static io.dockstore.common.CommonTestUtilities.runOldDockstoreClient;

/**
 * Tests a variety of basic dockstore CLI commands along with some tool commands
 * using an older (CommonTestUtilities.OLD_DOCKSTORE_VERSION) dockstore client
 * Testing Dockstore CLI 1.3.6 at the time of creation
 * @author gluu
 * @since 1.4.0
 */
@Category({ RegressionTest.class })
public class ClientRegressionIT extends BaseIT {
    @ClassRule
    public static TemporaryFolder temporaryFolder = new TemporaryFolder();
    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().enableLog();
    @Rule
    public final SystemErrRule systemErrRule = new SystemErrRule().enableLog();
    @Rule
    public final ExpectedSystemExit systemExit = ExpectedSystemExit.none();
    static URL url;
    static File dockstore;
    static File testJson;

    @BeforeClass
    public static void getOldDockstoreClient() throws IOException {
        TestUtility.createFakeDockstoreConfigFile();
        url = new URL("https://github.com/ga4gh/dockstore/releases/download/" + OLD_DOCKSTORE_VERSION + "/dockstore");
        dockstore = temporaryFolder.newFile("dockstore");
        FileUtils.copyURLToFile(url, dockstore);
        dockstore.setExecutable(true);
        url = new URL("https://raw.githubusercontent.com/DockstoreTestUser/dockstore_parameter_test/master/test.cwl.json");
        testJson = temporaryFolder.newFile("test.cwl.json");
        FileUtils.copyURLToFile(url, testJson);
    }

    @Before
    @Override
    public void resetDBBetweenTests() throws Exception {
        CommonTestUtilities.cleanStatePrivate1(SUPPORT);
        Client.DEBUG.set(false);
    }

    @Test
    public void testListEntriesOld() throws IOException, ApiException {
        String[] commandArray = new String[] { "--config", TestUtility.getConfigFileLocation(true), "tool", "list" };
        ImmutablePair<String, String> stringStringImmutablePair = runOldDockstoreClient(dockstore, commandArray);
        checkToolList(stringStringImmutablePair.getLeft());
    }

    @Test
    public void testDebugModeListEntriesOld() throws IOException, ApiException {
        String[] commandArray = new String[] { "--debug", "--config", TestUtility.getConfigFileLocation(true), "tool", "list" };
        ImmutablePair<String, String> stringStringImmutablePair = runOldDockstoreClient(dockstore, commandArray);
        checkToolList(stringStringImmutablePair.getLeft());
    }

    @Test
    public void testPluginEnableOldClient() throws ExecuteException {
        String[] commandArray1 = new String[] { "--config", ResourceHelpers.resourceFilePath("pluginsTest1/configWithPlugins"), "plugin",
                "download" };
        ImmutablePair<String, String> stringStringImmutablePair1 = runOldDockstoreClient(dockstore, commandArray1);
        String[] commandArray2 = new String[] { "--config", ResourceHelpers.resourceFilePath("pluginsTest1/configWithPlugins"), "plugin",
                "list" };
        ImmutablePair<String, String> stringStringImmutablePair2 = runOldDockstoreClient(dockstore, commandArray2);
        String stdout = stringStringImmutablePair2.getLeft();
        Assert.assertTrue(stdout.contains("dockstore-file-synapse-plugin"));
        Assert.assertTrue(stdout.contains("dockstore-file-s3-plugin"));
        Assert.assertFalse(stdout.contains("dockstore-icgc-storage-client-plugin"));
    }

    @Test
    public void testPluginDisableOldClient() throws ExecuteException {
        String[] commandArray = new String[] { "--config", ResourceHelpers.resourceFilePath("pluginsTest2/configWithPlugins"), "plugin",
                "download" };
        ImmutablePair<String, String> stringStringImmutablePair = runOldDockstoreClient(dockstore, commandArray);
        commandArray = new String[] { "--config", ResourceHelpers.resourceFilePath("pluginsTest2/configWithPlugins"), "plugin", "list" };
        stringStringImmutablePair = runOldDockstoreClient(dockstore, commandArray);
        String stdout = stringStringImmutablePair.getLeft();
        Assert.assertFalse(stdout.contains("dockstore-file-synapse-plugin"));
        Assert.assertFalse(stdout.contains("dockstore-file-s3-plugin"));
        Assert.assertTrue(stdout.contains("dockstore-file-icgc-storage-client-plugin"));
    }

    @Test
    public void testMetadataMethodsOld() throws IOException {
        String commandArray[] = new String[] { "--config", TestUtility.getConfigFileLocation(true), "--version" };
        ImmutablePair<String, String> stringStringImmutablePair;
        try {
            stringStringImmutablePair = runOldDockstoreClient(dockstore, commandArray);
            Assert.assertTrue(stringStringImmutablePair.getLeft().contains("Dockstore version 1.3.6"));
        } catch (Exception e) {
            // Sometimes there's an error: Can't find the latest version. Something might be wrong with the connection to Github.
        }
        commandArray = new String[] { "--config", TestUtility.getConfigFileLocation(true), "--server-metadata" };
        stringStringImmutablePair = runOldDockstoreClient(dockstore, commandArray);
        Assert.assertTrue(stringStringImmutablePair.getLeft().contains("version"));
        systemOutRule.clearLog();
    }

    @Test
    public void testCacheCleaningOld() throws IOException {
        ImmutablePair<String, String> stringStringImmutablePair = runOldDockstoreClient(dockstore,
                new String[] { "--config", TestUtility.getConfigFileLocation(true), "--clean-cache" });
        systemOutRule.clearLog();
    }

    @Test
    public void pluginDownloadOld() throws IOException {
        String[] commandArray = new String[] { "--config", TestUtility.getConfigFileLocation(true), "plugin", "download" };
        ImmutablePair<String, String> stringStringImmutablePair = runOldDockstoreClient(dockstore, commandArray);
        systemOutRule.clearLog();
    }

    /**
     * Tests that the unpublished tool can be published, refreshed, then launched once the json and input file is attained
     *
     * @throws ExecuteException
     */
    @Test
    public void testActualToolLaunch() throws ExecuteException {
        // manual publish the workflow
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file.txt"), "tool", "publish", "--entry",
                        "quay.io/dockstoretestuser/test_input_json", "--script" });
        // launch the workflow
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file.txt"), "tool", "refresh", "--entry",
                        "quay.io/dockstoretestuser/test_input_json", "--script" });
        String[] commandArray = { "--config", ResourceHelpers.resourceFilePath("config_file.txt"), "tool", "launch", "--entry",
                "quay.io/dockstoretestuser/test_input_json", "--json", testJson.getAbsolutePath(), "--script" };
        ImmutablePair<String, String> stringStringImmutablePair = runOldDockstoreClient(dockstore, commandArray);
        Assert.assertTrue("Final process status was not a success",
                (stringStringImmutablePair.getLeft().contains("Final process status is success")));
        Assert.assertTrue("Final process status was not a success",
                (stringStringImmutablePair.getRight().contains("Final process status is success")));

    }

}
