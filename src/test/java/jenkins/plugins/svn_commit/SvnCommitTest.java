package jenkins.plugins.svn_commit;

import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.FreeStyleProject;
import hudson.scm.SubversionSCM;
import hudson.scm.subversion.UpdateUpdater;

import java.io.File;
import java.io.IOException;
import java.util.Date;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.HudsonHomeLoader.CopyExisting;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.TestEnvironment;

public class SvnCommitTest {
	static {
		TestPluginManagerCleanup.registerCleanup();
	}

	@Rule
	public JenkinsRule j = new JenkinsRule() {
		protected void after() throws Exception {
			super.after();
			if (TestEnvironment.get() != null) {
				try {
					TestEnvironment.get().dispose();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		};
	};

	@Test
	public void first() throws Exception {
		FreeStyleProject project = j.createFreeStyleProject();
		File repo = new CopyExisting(getClass().getResource("simple.zip"))
				.allocate();
		SubversionSCM scm = new SubversionSCM("file://"
				+ repo.toURI().toURL().getPath());
		scm.setWorkspaceUpdater(new UpdateUpdater());
		project.setScm(scm);
		project.getBuildersList().add(new TestBuilder() {

			@Override
			public boolean perform(AbstractBuild<?, ?> build,
					Launcher launcher, BuildListener listener)
					throws InterruptedException, IOException {
				build.getWorkspace().child("a")
						.write(new Date().toString(), "UTF-8");
				return true;
			}
		});
		project.getPublishersList().add(new SvnCommitPublisher("simple test"));
		FreeStyleBuild build = project.scheduleBuild2(0).get();
		System.out.println(build.getLog(1000));
		j.assertBuildStatus(Result.SUCCESS, build);
	}
}
