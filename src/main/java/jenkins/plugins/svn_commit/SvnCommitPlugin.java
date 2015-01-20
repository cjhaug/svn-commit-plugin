package jenkins.plugins.svn_commit;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.FilePath.FileCallable;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.remoting.VirtualChannel;
import hudson.scm.SvnClientManager;
import hudson.scm.SubversionSCM;
import hudson.scm.SubversionSCM.ModuleLocation;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.codehaus.groovy.control.CompilerConfiguration;
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

public class SvnCommitPlugin {

	private SvnCommitPlugin() {
	}

	static boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener, String commitComment)
			throws InterruptedException, IOException {

		PrintStream logger = listener.getLogger();

		if (Result.SUCCESS != build.getResult()) {
			logger.println(Messages.UnsuccessfulBuild());
			return true;
		}

		AbstractProject<?, ?> rootProject = build.getProject().getRootProject();
		AbstractBuild<?, ?> rootBuild = build.getRootBuild();

		if (!(rootProject.getScm() instanceof SubversionSCM)) {
			logger.println(Messages.NotSubversion(rootProject.getScm()
					.toString()));
			return true;
		}

		SubversionSCM scm = SubversionSCM.class.cast(rootProject.getScm());
		EnvVars envVars = rootBuild.getEnvironment(listener);
		scm.buildEnvVars(rootBuild, envVars);

		String evalCommitComment = evalGroovyExpression(envVars, commitComment);
		SVNProperties revProps = new SVNProperties();
		revProps.put("jenkins:svn-commit", "true");

		// iterate over known SVN locations
		FilePath workspace = build.getWorkspace().absolutize();
		for (SubversionSCM.ModuleLocation ml : scm.getLocations(envVars,
				rootBuild)) {

			CommitTask commitTask = new CommitTask(build, scm, ml, listener,
					evalCommitComment, revProps);
			workspace.act(commitTask);

		}

		return true;
	}

	static String evalGroovyExpression(Map<String, String> env, String evalText) {
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

	private static class CommitTask implements FileCallable<Boolean>,
			Serializable {

		private static final long serialVersionUID = 8940690511619984733L;
		private ISVNAuthenticationProvider authProvider;
		private SvnClientManager clientManager;
		private TaskListener listener;
		private String commitComment;
		private SVNProperties revProps;

		public CommitTask(Run<?, ?> build, SubversionSCM scm,
				ModuleLocation location, TaskListener listener,
				String commitComment, SVNProperties revProps) {
			this.authProvider = scm.createAuthenticationProvider(
					build.getParent(), location);
			this.listener = listener;
			this.commitComment = commitComment;
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
						false, false, false, csHandler, null);
				if (csHandler.hasChangedFiles()) {
					SVNCommitInfo svnInfo = commitClient.doCommit(
							csHandler.getChangedFiles(), false, commitComment,
							revProps, null, false, false, SVNDepth.EMPTY);
					logger.println(Messages.Commited(svnInfo.getNewRevision()));
				}
			} catch (SVNException e) {
				logger.println(Messages.CommitFailed(e.getLocalizedMessage()));
				return false;
			}
			try {
				return true;
			} finally {
				clientManager.dispose();
			}
		}

	}

	public final static class SvnCommitStatusHandler implements
			ISVNStatusHandler {

		private List<File> changedFiles;

		public SvnCommitStatusHandler() {
			this.changedFiles = new ArrayList<File>();
		}

		public void handleStatus(SVNStatus status) throws SVNException {
			if (SVNStatusType.STATUS_MODIFIED.equals(status.getNodeStatus())) {
				this.changedFiles.add(status.getFile());
			}
		}

		public boolean hasChangedFiles() {
			return !changedFiles.isEmpty();
		}

		public File[] getChangedFiles() {
			return this.changedFiles
					.toArray(new File[this.changedFiles.size()]);
		}
	}
}
