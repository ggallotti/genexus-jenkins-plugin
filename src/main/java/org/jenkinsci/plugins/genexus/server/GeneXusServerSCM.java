/*
 * The MIT License
 *
 * Copyright 2018 GeneXus S.A..
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.genexus.server;

import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.msbuild.MsBuildBuilder;
import hudson.scm.ChangeLogParser;
import hudson.scm.PollingResult;
import hudson.scm.PollingResult.Change;
import hudson.scm.SCM;
import hudson.scm.SCMDescriptor;
import hudson.scm.SCMRevisionState;
import hudson.security.ACL;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.io.IOCase;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.genexus.GeneXusInstallation;
import org.jenkinsci.plugins.genexus.helpers.MsBuildArgsHelper;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 *
 * @author jlr
 */
public class GeneXusServerSCM extends SCM implements Serializable {

    // GX installation
    private final String gxInstallationId;
    
    // GXserver connection data
    private final String serverURL;
    private final String credentialsId;

    // KB info
    private final String kbName;
    private final String kbVersion;

    // Local KB DB info
    private final String kbDbServerInstance;
    private final String kbDbCredentialsId;
    private final String kbDbName;
    private boolean kbDbInSameFolder = true;

    @DataBoundConstructor
    public GeneXusServerSCM(
            String gxInstallationId,
            String serverURL,
            String credentialsId,
            String kbName,
            String kbVersion,
            String kbDbServerInstance,
            String kbDbCredentialsId,
            String kbDbName,
            boolean kbDbInSameFolder) {

        this.gxInstallationId = gxInstallationId;
        
        this.serverURL = serverURL;
        this.credentialsId = credentialsId;

        this.kbName = kbName;
        this.kbVersion = kbVersion;

        this.kbDbServerInstance = kbDbServerInstance;
        this.kbDbCredentialsId = kbDbCredentialsId;
        this.kbDbName = kbDbName;
        this.kbDbInSameFolder = kbDbInSameFolder;
    }

    @Exported
    public String getGxInstallationId() {
        return gxInstallationId;
    }
    
    private GeneXusInstallation getGeneXusInstallation() {
        return GeneXusInstallation.getInstallation(gxInstallationId);
    }
    
    private String getGxPath() {
        GeneXusInstallation installation = getGeneXusInstallation();
        if (installation!=null) {
            return installation.getHome();
        }
        
        return "";
    }

    private String getMSBuildInstallationId() {
        GeneXusInstallation installation = getGeneXusInstallation();
        if (installation!=null) {
            return installation.getMsBuildInstallationId();
        }
        
        return "";
    }

    @Exported
    public String getServerURL() {
        return serverURL;
    }

    @Exported
    public String getCredentialsId() {
        return credentialsId;
    }

    @Exported
    public String getKbName() {
        return kbName;
    }

    @Exported
    public String getKbVersion() {
        return kbVersion;
    }

    @Exported
    public String getKbDbServerInstance() {
        return kbDbServerInstance;
    }

    @Exported
    public String getKbDbCredentialsId() {
        return kbDbCredentialsId;
    }

    @Exported
    public String getKbDbName() {
        return kbDbName;
    }

    @Exported
    public boolean isKbDbInSameFolder() {
        return kbDbInSameFolder;
    }

    @Override
    public ChangeLogParser createChangeLogParser() {
        return new GXSChangeLogParser();
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Override
    public PollingResult compareRemoteRevisionWith(@Nonnull Job<?, ?> project, @Nullable Launcher launcher, @Nullable FilePath workspace, @Nonnull TaskListener listener, @Nonnull SCMRevisionState _baseline) throws IOException, InterruptedException {
        final GXSRevisionState baseline = getSafeBaseline(project, launcher, workspace, listener, _baseline);
        
        FilePath workingPath = workspace;
        if (workspace == null)
            workingPath = new FilePath(project.getRootDir());
        
        GXSConnection gxs = new GXSConnection(getServerURL(), getCredentialsId(), getKbName(), getKbVersion());
        GXSInfo currentInfo = workingPath.act(new GetLastRevisionTask(listener, getGxPath(), gxs, baseline.getRevisionDate(), new Date()));
        GXSRevisionState currentState = new GXSRevisionState(currentInfo.revision, currentInfo.revisionDate);

        return new PollingResult(baseline, currentState, currentState.getRevision() > baseline.getRevision() ? Change.SIGNIFICANT : Change.NONE);
    }

    @Nonnull
    private GXSRevisionState getSafeBaseline(@Nonnull Job<?, ?> project, @Nullable Launcher launcher, @Nullable FilePath workspace, @Nonnull TaskListener listener, @Nonnull SCMRevisionState _baseline) throws IOException, InterruptedException {
        GXSRevisionState baseline = null;
        if (_baseline instanceof GXSRevisionState) {
            baseline = (GXSRevisionState) _baseline;
        } else if (project.getLastBuild() != null) {
            baseline = (GXSRevisionState) calcRevisionsFromBuild(project.getLastBuild(), launcher != null ? workspace
                    : null, launcher, listener);
        }
        
        if (baseline == null) {
            baseline = GXSRevisionState.MIN_REVISION;
        }
        
        return baseline;
    }
    
    /**
     * Please consider using the non-static version
     * {@link #parseGxServerRevisionFile(Run)}!
     */
    static GXSRevisionState parseRevisionFile(Run<?, ?> build) throws IOException {
        return parseRevisionFile(build, true);
    }

    GXSRevisionState parseGxServerRevisionFile(Run<?, ?> build) throws IOException {
        return parseRevisionFile(build);
    }

    /**
     * Reads the revision file of the specified build (or the closest, if the
     * flag is so specified.)
     *
     * @param findClosest If true, this method will go back the build history
     * until it finds a revision file. A build may not have a revision file for
     * any number of reasons (such as failure, interruption, etc.)
     * @return a GXSRevisionState which includes a revision number and date
     */
    @Nonnull
    static GXSRevisionState parseRevisionFile(Run<?, ?> build, boolean findClosest) throws IOException {

        if (findClosest) {
            for (Run<?, ?> b = build; b != null; b = b.getPreviousBuild()) {
                if (getRevisionFile(b).exists()) {
                    build = b;
                    break;
                }
            }
        }

        File file = getRevisionFile(build);
        if (!file.exists()) // nothing to compare against
            return GXSRevisionState.MIN_REVISION;

        GXSInfo info = loadRevisionFile(file);
        return new GXSRevisionState(info.revision, info.revisionDate);
    }

    /**
     * Polling can happen on the master and does not require a workspace.
     */
    @Override
    public boolean requiresWorkspaceForPolling() {
        return false;
    }

    @Override
    public void checkout(Run<?, ?> build, Launcher launcher, FilePath workspace, TaskListener listener,
            File changelogFile, SCMRevisionState baseline) throws IOException, InterruptedException {

        LOGGER.log(Level.INFO, "Checking out (or updating) {0} from {1} with {2}", new Object[]{getKbName(), getServerURL(), getCredentialsId()});

        if (build instanceof AbstractBuild && listener instanceof BuildListener) {

            // TODO: Add support for parameterized builds
            // hint: see how SubversionSCM.java uses EnvVarsUtils to override env variables
            // with values from build.getBuildVariables() and then passes the
            // overritten values to nested taks
            /*
            EnvVars env = build.getEnvironment(listener);
            if (build instanceof AbstractBuild) {
                EnvVarsUtils.overrideAll(env, ((AbstractBuild) build).getBuildVariables());
            }
             */
            Builder builder = createCheckoutOrUpdateAction(workspace);

            // TODO: we should get the actual revision as an output from the checkout or update
            // Meanwhile we resort to get the latest revision up to the current time
            Date updateTimeStamp = new Date();
            if (!builder.perform((AbstractBuild) build, launcher, (BuildListener) listener))
                throw new IOException("error executing checkout");

            GXSConnection gxs = new GXSConnection(getServerURL(), getCredentialsId(), getKbName(), getKbVersion());
            GXSInfo info = workspace.act(new GetLastRevisionTask(listener, getGxPath(), gxs, null, updateTimeStamp));
            saveRevisionFile(build, info);

            if (changelogFile != null) {
                calcChangeLog(build, workspace, changelogFile, baseline, listener, gxs, info);
            }
        }
    }

    /**
     * Called after checkout/update has finished to compute the changelog.
     */
    private void calcChangeLog(Run<?, ?> build, FilePath workspace, File changelogFile, SCMRevisionState baseline, TaskListener listener, GXSConnection gxs, GXSInfo currentInfo) throws IOException, InterruptedException {
        
        GXSRevisionState _baseline = getSafeBaseline(build, baseline);
        
        boolean created = false;
        if (currentInfo.revisionDate.after(_baseline.getRevisionDate())) {
            created = workspace.act(new CreateLogTask(listener, getGxPath(), gxs, changelogFile, _baseline.getRevisionDate(), currentInfo.revisionDate));
        }
        
        if (!created) {
            createEmptyChangeLog(changelogFile, listener, "log");
        }
    }

    private GXSRevisionState getSafeBaseline(Run<?, ?> build, SCMRevisionState baseline) throws IOException {
        GXSRevisionState _baseline = GXSRevisionState.MIN_REVISION;
        if (baseline instanceof GXSRevisionState ) {
            _baseline = (GXSRevisionState) baseline;
        } else if (build != null) {
            // build is the current one, we are looking for a 'baseline'
            build = build.getPreviousBuild();
            if (build != null) {
                // parseRevisionFile() keeps going back looking for a previous
                // build with a revision file
                _baseline = parseRevisionFile(build, /* findClosest= */ true); 
            }
        }
        
        return _baseline;
    }
    
    private static void saveRevisionFile(Run<?, ?> build, GXSInfo info) throws IOException {
        saveRevisionFile(getRevisionFile(build), info);
    }

    private static void saveRevisionFile(File file, GXSInfo info) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.writeValue(file, info);
    }

    private static GXSInfo loadRevisionFile(File file) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(file, GXSInfo.class);
    }

    private Builder createCheckoutOrUpdateAction(FilePath workspace) {
        if (!kbAlreadyExists(workspace.child(getKbName()))) {
            return createCheckoutAction(workspace);
        }

        return createUpdateAction(workspace);
    }

    private MsBuildArgsHelper createBaseMsBuildArgs(FilePath workspace, String... targetNames) {
        MsBuildArgsHelper msbArgs = new MsBuildArgsHelper(targetNames);

        msbArgs.addProperty("GX_PROGRAM_DIR", getGxPath());

        StandardCredentials credentials = lookupCredentials(getCredentialsId(), getServerURL());
        if (credentials instanceof StandardUsernamePasswordCredentials) {
            StandardUsernamePasswordCredentials upCredentials = (StandardUsernamePasswordCredentials) credentials;
            msbArgs.addProperty("ServerUsername", upCredentials.getUsername());
            msbArgs.addProperty("ServerPassword", upCredentials.getPassword().getPlainText());
        }

        if (StringUtils.isNotBlank(getKbVersion())) {
            msbArgs.addProperty("ServerKbVersion", getKbVersion());
        }

        FilePath kbPath = workspace.child(getKbName());
        msbArgs.addProperty("WorkingDirectory", kbPath);

        return msbArgs;
    }

    private Builder createUpdateAction(FilePath workspace) {
        MsBuildArgsHelper msbArgs = createBaseMsBuildArgs(workspace, "Update");
        return createMsBuildAction(msbArgs);
    }

    private Builder createCheckoutAction(FilePath workspace) {
        MsBuildArgsHelper msbArgs = createBaseMsBuildArgs(workspace, "Checkout");

        msbArgs.addProperty("ServerUrl", getServerURL());
        msbArgs.addProperty("ServerKbAlias", getKbName());

        // TODO: Add support for including all versions on checkout
        msbArgs.addProperty("GetAllKbVersions", false);

        if (StringUtils.isNotBlank(getKbDbServerInstance())) {
            msbArgs.addProperty("DbaseServerInstance", getKbDbServerInstance());
        }

        StandardCredentials dbCredentials = lookupCredentials(getKbDbCredentialsId(), getKbDbServerInstance());
        if (dbCredentials instanceof StandardUsernamePasswordCredentials) {
            StandardUsernamePasswordCredentials upCredentials = (StandardUsernamePasswordCredentials) dbCredentials;
            msbArgs.addProperty("DbaseUseIntegratedSecurity", false);
            msbArgs.addProperty("DbaseServerUsername", upCredentials.getUsername());
            msbArgs.addProperty("DbaseServerPassword", upCredentials.getPassword().getPlainText());
        }

        msbArgs.addProperty("DbaseName", getSafeKbDbName(getKbName(), getKbDbName()));
        msbArgs.addProperty("CreateDbInKbFolder", isKbDbInSameFolder());

        return createMsBuildAction(msbArgs);
    }

    private String getMsBuildFile() {
        final String teamDevMsBuildFile = "TeamDev.msbuild";
        Path teamDevPath = Paths.get(getGxPath(), teamDevMsBuildFile);
        return teamDevPath.toString();
    }
    
    private Builder createMsBuildAction(MsBuildArgsHelper msbArgs) {
        MsBuildBuilder builder = new MsBuildBuilder(
                getMSBuildInstallationId(),
                getMsBuildFile(),
                msbArgs.toString(),
                true,
                false,
                false,
                false
        );

        return builder;
    }

    private static String getSafeKbDbName(String kbName, String kbDbName) {
        if (StringUtils.isNotBlank(kbDbName)) {
            return kbDbName;
        }

        return "GX_KB_" + kbName + "_" + UUID.randomUUID().toString();
    }

    /**
     * Gets the file that stores the revision.
     * 
     * @param build a build instance for which the revision file is requested
     * @return File that stores the revision
     */
    public static File getRevisionFile(Run build) {
        return new File(build.getRootDir(), "revision.txt");
    }

    private static StandardCredentials lookupCredentials(String credentialsId, String serverURL) {
        return credentialsId == null ? null
                : CredentialsMatchers.firstOrNull(
                        CredentialsProvider.lookupCredentials(
                                StandardCredentials.class,
                                Jenkins.getInstance(),
                                ACL.SYSTEM,
                                URIRequirementBuilder.fromUri(serverURL).build()
                        ),
                        CredentialsMatchers.withId(credentialsId)
                );
    }

    @Override
    @Nonnull
    public SCMRevisionState calcRevisionsFromBuild(@Nonnull Run<?, ?> build, @Nullable FilePath workspace, @Nullable Launcher launcher,
            @Nonnull TaskListener listener) throws IOException, InterruptedException {
        return parseRevisionFile(build, true);
    }

    private static final Logger LOGGER = Logger.getLogger(GeneXusServerSCM.class.getName());

    private boolean kbAlreadyExists(FilePath workspace) {
        try {
            return !(workspace.list(new WildcardFileFilter("*.gxw", IOCase.INSENSITIVE)).isEmpty());
        } catch (Exception ex) {
            Logger.getLogger(GeneXusServerSCM.class.getName()).log(Level.SEVERE, null, ex);
            return false;
        }
    }

    @Extension
    public static class DescriptorImpl extends SCMDescriptor<GeneXusServerSCM> {

        public static final String DEFAULT_GENEXUS_PATH = "C:\\Program Files (x86)\\GeneXus\\GeneXus15";
        public static final String DEFAULT_SERVER_URL = "https://sandbox.genexusserver.com/v15";

        @Override
        public boolean isApplicable(Job project) {
            return true;
        }

        public DescriptorImpl() {
            super(GeneXusServerSCM.class, null);
            load();
        }

        @Override
        public SCM newInstance(@Nullable StaplerRequest req, @Nonnull JSONObject formData) throws FormException {
            return super.newInstance(req, formData);
        }

        @Override
        public String getDisplayName() {
            return "GeneXus Server";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            // Save configuration
            save();
            return super.configure(req, formData);
        }
        
        public ListBoxModel doFillGxInstallationIdItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("(Default)", "");
            for (GeneXusInstallation installation : GeneXusInstallation.getInstallations()) {
                items.add(installation.getName(), installation.getName());
            }
            return items;
        }
        
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item item, @QueryParameter String credentialsId, @QueryParameter String serverURL) {
            StandardListBoxModel result = new StandardListBoxModel();
            if (!userCanSelect(item)) {
                return result;
            }

            String url = Util.fixEmptyAndTrim(serverURL);

            List<DomainRequirement> reqs = (url == null)
                    ? Collections.<DomainRequirement>emptyList()
                    : URIRequirementBuilder.fromUri(url).build();

            CredentialsMatcher matcher = CredentialsMatchers.anyOf(CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class));

            return result
                    .includeEmptyValue()
                    .includeMatchingAs(ACL.SYSTEM, item, StandardUsernamePasswordCredentials.class, reqs, matcher)
                    .includeCurrentValue(credentialsId);
        }

        public ListBoxModel doFillKbDbCredentialsIdItems(@AncestorInPath Item item, @QueryParameter String kbDbCredentialsId, @QueryParameter String kbDbServerInstance) {
            StandardListBoxModel result = new StandardListBoxModel();
            if (!userCanSelect(item)) {
                return result;
            }

            String url = Util.fixEmptyAndTrim(kbDbServerInstance);

            List<DomainRequirement> reqs = (url == null)
                    ? Collections.<DomainRequirement>emptyList()
                    : URIRequirementBuilder.fromUri(url).build();

            CredentialsMatcher matcher = CredentialsMatchers.anyOf(CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class));

            return result
                    .includeEmptyValue()
                    .includeMatchingAs(ACL.SYSTEM, item, StandardUsernamePasswordCredentials.class, reqs, matcher)
                    .includeCurrentValue(kbDbCredentialsId);
        }

        private Boolean userCanSelect(Item item) {
            if (item == null) {
                return Jenkins.getInstance().hasPermission(Jenkins.ADMINISTER);
            }

            return (item.hasPermission(Item.EXTENDED_READ)
                    || item.hasPermission(CredentialsProvider.USE_ITEM));
        }

        /**
         * Validate the value for a GeneXus Server connection.
         * 
         * @param value URL of a GeneXus Server installation
         * @return a FormValidation of a specific kind (OK, ERROR, WARNING)
         */
        public FormValidation doCheckServerURL(@QueryParameter String value) {

            // repository URL is required
            String url = Util.fixEmptyAndTrim(value);
            if (url == null) {
                return FormValidation.error("Server URL is required");
            }

            // repository URL syntax
            try {
                new URL(url);
            } catch (MalformedURLException ex) {
                LOGGER.log(Level.SEVERE, ex.getMessage());
                return FormValidation.error("Invalid Server URL. " + ex.getMessage());
            }

            return FormValidation.ok();
        }

        /**
         * Validate the value for GeneXus Server credentials.
         * 
         * @param item Item to which the credentials apply
         * @param value id of credentials to validate
         * @param serverURL URL of a GeneXus Server installation
         * @return a FormValidation of a specific kind (OK, ERROR, WARNING)
         */
        @RequirePOST
        public FormValidation doCheckCredentialsId(@AncestorInPath Item item, @QueryParameter String value, @QueryParameter String serverURL) {
            if (!userCanSelect(item)) {
                return FormValidation.ok();
            }

            if (value == null || value.isEmpty()) {
                return FormValidation.ok(); // pre v15 GXservers may allow using no credentials
            }
            
            String url = Util.fixEmptyAndTrim(serverURL);
            if (url == null) {
                return FormValidation.ok();
            }

            try {
                StandardCredentials credentials = lookupCredentials(item, value, url);
                if (credentials == null) {
                    return FormValidation.error("Cannot find currently selected credentials");
                }
                // TODO: additional checks
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, e.getMessage());
                return FormValidation.error("Unable to access the repository");
            }

            return FormValidation.ok();
        }

        private static StandardCredentials lookupCredentials(Item context, String credentialsId, String serverURL) {
            return credentialsId == null ? null
                    : CredentialsMatchers.firstOrNull(CredentialsProvider.lookupCredentials(StandardCredentials.class,
                            context,
                            ACL.SYSTEM,
                            URIRequirementBuilder.fromUri(serverURL).build()
                    ),
                            CredentialsMatchers.withId(credentialsId)
                    );
        }

        /**
         * Validate the value for a SQL Server Instance.
         * 
         * @param value SQL Server instance
         * @return a FormValidation of a specific kind (OK, ERROR, WARNING)
         */
        public FormValidation doCheckKbDbServerInstance(@QueryParameter String value) {
            return FormValidation.ok();
        }

        /**
         * Validate the value for the SQL Server credentials.
         * 
         * @param item Item to which the credentials apply
         * @param value id of credentials to validate
         * @param kBDbServerInstance SQL Server instance used for the KB
         * @return a FormValidation of a specific kind (OK, ERROR, WARNING)
         */
        @RequirePOST
        public FormValidation doCheckKbDbCredentialsId(@AncestorInPath Item item, @QueryParameter String value, @QueryParameter String kBDbServerInstance) {
            if (!userCanSelect(item)) {
                return FormValidation.ok();
            }

            if (value == null || value.isEmpty()) {
                return FormValidation.ok(); // pre v15 GXservers may allow using no credentials
            }
            
            String url = Util.fixEmptyAndTrim(kBDbServerInstance);
            if (url == null) {
                return FormValidation.ok();
            }

            try {
                StandardCredentials credentials = lookupKbDbCredentials(item, value, url);
                if (credentials == null) {
                    return FormValidation.error("Cannot find currently selected credentials");
                }
                // TODO: additional checks
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, e.getMessage());
                return FormValidation.error("Unable to access the server");
            }

            return FormValidation.ok();
        }

        private static StandardCredentials lookupKbDbCredentials(Item context, String kbDbCredentialsId, String kbDbServerInstance) {
            return kbDbCredentialsId == null ? null
                    : CredentialsMatchers.firstOrNull(CredentialsProvider
                            .lookupCredentials(StandardCredentials.class, context, ACL.SYSTEM,
                                    URIRequirementBuilder.fromUri(kbDbServerInstance).build()),
                            CredentialsMatchers.withId(kbDbCredentialsId));
        }
    }
}
