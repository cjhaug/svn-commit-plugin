package jenkins.plugins.svncommit;

import java.io.File;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.lang.StringUtils;
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
import hudson.util.StreamCopyThread;
import net.sf.json.JSONObject;

/**
 * Execute Subversion commit for successful build.
 *
 * @author Christian Haug
 */
public class SvnCommitPublisher extends Notifier {

	public final static String LOG_PREFIX = "[SVN-COMMIT] ";

	/** Subversion commit comment. */
	public final String commitComment;
	/** Include ignored files. */
	public final boolean includeIgnored;

	/**
	 * Creates a new instance of <code>SvnCommitPublisher</code>.
	 * 
	 * @param commitComment
	 *            Subversion commit comment
	 * @param includeIgnored
	 *            Include ignored files
	 */
	@DataBoundConstructor
	public SvnCommitPublisher(final String commitComment,
			final boolean includeIgnored) {
		this.commitComment = StringUtils.stripToEmpty(commitComment);
		this.includeIgnored = includeIgnored;
	}

	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.NONE;
	}

	@Override
	public SvnCommitDescriptorImpl getDescriptor() {
		return (SvnCommitDescriptorImpl) super.getDescriptor();
	}

	@Override
	public boolean perform(final AbstractBuild<?, ?> build,
			final Launcher launcher, final BuildListener listener)
					throws InterruptedException, IOException {
		PrintStream logger = listener.getLogger();

		if (Result.SUCCESS != build.getResult()) {
			logger.println(LOG_PREFIX + Messages.UnsuccessfulBuild());
			return true;
		}

		try {
			AbstractProject<?, ?> rootProject = build.getProject()
					.getRootProject();
			AbstractBuild<?, ?> rootBuild = build.getRootBuild();

			if (!(rootProject.getScm() instanceof SubversionSCM)) {
				logger.println(LOG_PREFIX + Messages
						.NotSubversion(rootProject.getScm().getType()));
				return true;
			}
			SubversionSCM scm = SubversionSCM.class.cast(rootProject.getScm());

			// evaluate commit comment
			EnvVars envVars = rootBuild.getEnvironment(listener);
			scm.buildEnvVars(rootBuild, envVars);
			String evalCommitComment = getDescriptor()
					.evalGroovyExpression(envVars, commitComment);

			// subversion revision property
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
		} catch (InterruptedException e) {
			logger.println(LOG_PREFIX + e.getLocalizedMessage());
			return true;
		}
	}

	/**
	 * This object gets instantiated on the master and then sent to the slave
	 * via remoting, then used to {@linkplain #invoke(File, VirtualChannel)
	 * perform the actual commit activity}.
	 *
	 * <p>
	 * A number of contextual objects are defined as fields, to be used by the
	 * {@link #invoke(File, VirtualChannel)} method. These fields are set by
	 * {@link SvnCommitPublisher} before the invocation.
	 */
	private static class SvnCommitTask
			implements FileCallable<Boolean>, Serializable {

		private static final long serialVersionUID = 8940690511619984733L;
		/** Authentication provided by Jenkins master. */
		private ISVNAuthenticationProvider authProvider;
		/** Factory for various subversion commands. */
		private SvnClientManager clientManager;
		/** Connected to build console. */
		private TaskListener listener;
		/** Commit message */
		private String commitComment;
		/** Include ignored files. */
		private boolean includeIgnored;
		/** Subversion revision properties. */
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

			PipedOutputStream pos = new PipedOutputStream();
			StreamCopyThread sct = new StreamCopyThread("svn commit copier",
					new PipedInputStream(pos), logger);
			sct.start();

			try {
				SVNCommitClient commitClient = clientManager.getCommitClient();
				commitClient.setEventHandler(
						new SvnCommitEventHandler(new PrintStream(pos), file));
				SVNStatusClient statusClient = clientManager.getStatusClient();

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
				if (csHandler.hasCommitFiles()) {
					SVNCommitInfo svnInfo = commitClient.doCommit(
							csHandler.getCommitFiles(), false, commitComment,
							revProps, null, false, false, SVNDepth.EMPTY);
					if (svnInfo.equals(SVNCommitInfo.NULL)) {
						throw new IOException(LOG_PREFIX + Messages
								.CommitFailed(svnInfo.getErrorMessage()));
					}
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
			} finally {
				try {
					clientManager.dispose();
					pos.close();
				} finally {
					try {
						sct.join();
					} catch (InterruptedException e) {
						throw new IOException(e);
					}
				}
			}
			return true;
		}
	}

	/**
	 * Receive Subversion status information.
	 * 
	 * @author Christian Haug
	 *
	 */
	private final static class SvnCommitStatusHandler
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

		public boolean hasCommitFiles() {
			return !changedFiles.isEmpty() || !missingFiles.isEmpty()
					|| !unversionedFiles.isEmpty();
		}

		public File[] getCommitFiles() {
			List<File> commitFiles = new ArrayList<File>();
			commitFiles.addAll(changedFiles);
			commitFiles.addAll(unversionedFiles);
			commitFiles.addAll(missingFiles);
			return commitFiles.toArray(new File[commitFiles.size()]);
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
			} catch (InterruptedException e) {
				return FormValidation.error(e.getMessage());
			}
		}

		public String evalGroovyExpression(Map<String, String> env,
				String evalText) throws InterruptedException {
			Binding binding = new Binding();
			binding.setVariable("env", env);
			binding.setVariable("sys", System.getProperties());

			CompilerConfiguration config = new CompilerConfiguration();
			GroovyShell shell = new GroovyShell(binding, config);
			try {
				Object result = shell.evaluate("return \"" + evalText + "\"");
				if (result == null) {
					return "";
				} else {
					return result.toString().trim();
				}
			} catch (CompilationFailedException e) {
				throw new InterruptedException(
						Messages.BadGroovy(e.getMessage()));
			}
		}

	}
}
