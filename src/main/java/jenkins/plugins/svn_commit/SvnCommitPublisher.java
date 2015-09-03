package jenkins.plugins.svn_commit;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.tmatesoft.svn.core.SVNCancelException;
import org.tmatesoft.svn.core.SVNCommitInfo;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNProperties;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationProvider;
import org.tmatesoft.svn.core.wc.ISVNStatusHandler;
import org.tmatesoft.svn.core.wc.SVNCommitClient;
import org.tmatesoft.svn.core.wc.SVNStatus;
import org.tmatesoft.svn.core.wc.SVNStatusClient;
import org.tmatesoft.svn.core.wc.SVNStatusType;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.FilePath.FileCallable;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.scm.SubversionSCM;
import hudson.scm.SvnClientManager;
import hudson.scm.SubversionSCM.ModuleLocation;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import net.sf.json.JSONObject;

public class SvnCommitPublisher extends Notifier {

	public final static String LOG_PREFIX = "[SVN-COMMIT] ";

	public final String commitComment;
	public final boolean includeIgnored;

	@DataBoundConstructor
	public SvnCommitPublisher(final String commitComment,
			final boolean includeIgnored) {
		this.commitComment = commitComment;
		this.includeIgnored = includeIgnored;
	}

	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.BUILD;
	}

	@Override
	public SvnCommitDescriptorImpl getDescriptor() {
		return (SvnCommitDescriptorImpl) super.getDescriptor();
	}

	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener) throws InterruptedException, IOException {
		PrintStream logger = listener.getLogger();

		if (Result.SUCCESS != build.getResult()) {
			logger.println(LOG_PREFIX + Messages.UnsuccessfulBuild());
			return true;
		}

		AbstractProject<?, ?> rootProject = build.getProject().getRootProject();
		AbstractBuild<?, ?> rootBuild = build.getRootBuild();

		if (!(rootProject.getScm() instanceof SubversionSCM)) {
			logger.println(LOG_PREFIX
					+ Messages.NotSubversion(rootProject.getScm().getType()));
			return true;
		}

		SubversionSCM scm = SubversionSCM.class.cast(rootProject.getScm());
		EnvVars envVars = rootBuild.getEnvironment(listener);
		scm.buildEnvVars(rootBuild, envVars);

		String evalCommitComment = getDescriptor().evalGroovyExpression(envVars,
				commitComment);
		SVNProperties revProps = new SVNProperties();
		revProps.put("jenkins:svn-commit", "");

		// iterate over known SVN locations
		FilePath workspace = build.getWorkspace().absolutize();
		for (SubversionSCM.ModuleLocation ml : scm.getLocations(envVars,
				rootBuild)) {

			SvnCommitTask commitTask = new SvnCommitTask(build, scm, ml,
					listener, evalCommitComment, includeIgnored, revProps);
			workspace.act(commitTask);

		}

		return true;
	}

	private static class SvnCommitTask
			implements FileCallable<Boolean>, Serializable {

		private static final long serialVersionUID = 8940690511619984733L;
		private ISVNAuthenticationProvider authProvider;
		private SvnClientManager clientManager;
		private TaskListener listener;
		private String commitComment;
		private boolean includeIgnored;
		private SVNProperties revProps;

		public SvnCommitTask(Run<?, ?> build, SubversionSCM scm,
				ModuleLocation location, TaskListener listener,
				String commitComment, boolean includeIgnored,
				SVNProperties revProps) {
			this.authProvider = scm
					.createAuthenticationProvider(build.getParent(), location);
			this.listener = listener;
			this.commitComment = commitComment;
			this.includeIgnored = includeIgnored;
			this.revProps = revProps;
		}

		public Boolean invoke(File file, VirtualChannel channel)
				throws IOException, InterruptedException {
			PrintStream logger = listener.getLogger();
			clientManager = SubversionSCM.createClientManager(authProvider);

			SVNCommitClient commitClient = clientManager.getCommitClient();
			SVNStatusClient statusClient = clientManager.getStatusClient();

			try {
				SvnCommitStatusHandler csHandler = new SvnCommitStatusHandler();
				statusClient.doStatus(file, null, SVNDepth.INFINITY, false,
						false, includeIgnored, false, csHandler, null);
				// handle missing files
				if (csHandler.hasMissingFiles()) {
					File[] files = csHandler.getMissingFiles();
					for (int i = 0; i < files.length; i++) {
						clientManager.getWCClient().doDelete(files[i], true,
								false);
					}
				}
				// handle unversioned files
				if (csHandler.hasUnversionedFiles()) {
					clientManager.getWCClient().doAdd(
							csHandler.getUnversionedFiles(), true, false, false,
							SVNDepth.EMPTY, false, includeIgnored, true);
				}
				// commit changes
				SVNCommitInfo svnInfo = commitClient.doCommit(
						csHandler.getCommitFiles(), false, commitComment,
						revProps, null, false, false, SVNDepth.EMPTY);
				if (!svnInfo.equals(SVNCommitInfo.NULL)) {
					logger.println(LOG_PREFIX
							+ Messages.Commited(svnInfo.getNewRevision()));
				}
			} catch (SVNCancelException e) {
				listener.error(LOG_PREFIX
						+ Messages.CommitFailed(e.getLocalizedMessage()));
				throw (InterruptedException) new InterruptedException()
						.initCause(e);
			} catch (SVNException e) {
				listener.error(LOG_PREFIX
						+ Messages.CommitFailed(e.getLocalizedMessage()));
				throw new IOException(LOG_PREFIX
						+ Messages.CommitFailed(e.getLocalizedMessage()), e);
			}
			try {
				return true;
			} finally {
				clientManager.dispose();
			}
		}

	}

	public final static class SvnCommitStatusHandler
			implements ISVNStatusHandler {

		private List<File> changedFiles;
		private List<File> unversionedFiles;
		private List<File> missingFiles;

		public SvnCommitStatusHandler() {
			this.changedFiles = new ArrayList<File>();
			this.unversionedFiles = new ArrayList<File>();
			this.missingFiles = new ArrayList<File>();
		}

		public void handleStatus(SVNStatus status) throws SVNException {
			if (SVNStatusType.STATUS_MODIFIED.equals(status.getNodeStatus())
					|| SVNStatusType.STATUS_ADDED.equals(status.getNodeStatus())
					|| SVNStatusType.STATUS_DELETED
							.equals(status.getNodeStatus())) {
				this.changedFiles.add(status.getFile());
			} else if (SVNStatusType.STATUS_UNVERSIONED
					.equals(status.getNodeStatus())
					|| SVNStatusType.STATUS_IGNORED
							.equals(status.getNodeStatus())) {
				File file = status.getFile();
				this.unversionedFiles.add(file);
				if (file.isDirectory()) {
					this.unversionedFiles.addAll(FileUtils.listFilesAndDirs(
							file, TrueFileFilter.TRUE,
							FileFilterUtils.makeSVNAware(null)));
				}
			} else if (SVNStatusType.STATUS_MISSING
					.equals(status.getNodeStatus())) {
				this.missingFiles.add(status.getFile());
			}
		}

		public File[] getCommitFiles() {
			List<File> commitFiles = new ArrayList<File>();
			commitFiles.addAll(changedFiles);
			commitFiles.addAll(unversionedFiles);
			commitFiles.addAll(missingFiles);
			return commitFiles.toArray(new File[commitFiles.size()]);
		}

		public boolean hasChangedFiles() {
			return !changedFiles.isEmpty();
		}

		public File[] getChangedFiles() {
			return this.changedFiles
					.toArray(new File[this.changedFiles.size()]);
		}

		public boolean hasUnversionedFiles() {
			return !unversionedFiles.isEmpty();
		}

		public File[] getUnversionedFiles() {
			return this.unversionedFiles
					.toArray(new File[this.unversionedFiles.size()]);
		}

		public boolean hasMissingFiles() {
			return !missingFiles.isEmpty();
		}

		public File[] getMissingFiles() {
			return this.missingFiles
					.toArray(new File[this.missingFiles.size()]);
		}
	}

	@Extension
	public static final class SvnCommitDescriptorImpl
			extends BuildStepDescriptor<Publisher> {

		public final String commitComment;

		public SvnCommitDescriptorImpl() {
			commitComment = Messages.DefaultCommitComment();
			load();
		}

		@SuppressWarnings("rawtypes")
		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return true;
		}

		@Override
		public String getDisplayName() {
			return Messages.DisplayName();
		}

		@Override
		public boolean configure(StaplerRequest req, JSONObject formData)
				throws FormException {
			req.bindJSON(this, formData);
			save();

			return super.configure(req, formData);
		}

		public FormValidation doCheckCommitComment(
				@QueryParameter final String value) {
			try {
				evalGroovyExpression(new HashMap<String, String>(), value);
				return FormValidation.ok();
			} catch (CompilationFailedException e) {
				return FormValidation.error(Messages.BadGroovy(e.getMessage()));
			}
		}

		public String evalGroovyExpression(Map<String, String> env,
				String evalText) throws CompilationFailedException {
			Binding binding = new Binding();
			binding.setVariable("env", env);
			binding.setVariable("sys", System.getProperties());

			CompilerConfiguration config = new CompilerConfiguration();
			GroovyShell shell = new GroovyShell(binding, config);
			Object result = shell.evaluate("return \"" + evalText + "\"");
			if (result == null) {
				return "";
			} else {
				return result.toString().trim();
			}
		}

	}
}
