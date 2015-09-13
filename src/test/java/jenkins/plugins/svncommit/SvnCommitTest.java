package jenkins.plugins.svncommit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.HudsonHomeLoader.CopyExisting;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.scm.SubversionSCM;
import hudson.scm.subversion.UpdateUpdater;
import jenkins.plugins.svncommit.SvnCommitPublisher.SvnCommitDescriptorImpl;

public class SvnCommitTest {
	static {
		TestPluginManagerCleanup.registerCleanup();
	}

	@Rule
	public JenkinsRule j = new JenkinsRule();

	@Test
	public void testSvnCommit() throws Exception {
		FreeStyleProject project = j.createFreeStyleProject();
		File repo = new CopyExisting(getClass().getResource("simple.zip"))
				.allocate();
		SubversionSCM scm = new SubversionSCM(
				"file://" + repo.toURI().toURL().getPath());
		scm.setWorkspaceUpdater(new UpdateUpdater());
		project.setScm(scm);
		project.getBuildersList().add(new TestBuilder() {

			@Override
			public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
					BuildListener listener)
							throws InterruptedException, IOException {
				build.getWorkspace().child("a").write(new Date().toString(),
						"UTF-8");
				return true;
			}
		});
		project.getPublishersList()
				.add(new SvnCommitPublisher("simple test", false));
		FreeStyleBuild build = project.scheduleBuild2(0).get();
		j.assertBuildStatus(Result.SUCCESS, build);
	}

	@Test
	public void testCommitComment() throws Exception {
		SvnCommitPublisher publisher = new SvnCommitPublisher("", false);
		SvnCommitDescriptorImpl descriptor = publisher.getDescriptor();
		String msg;
		try {
			msg = descriptor.evalGroovyExpression(new HashMap<String, String>(),
					"expand environment variable ${env['missing quote]}");
			fail("bad Groovy expression should throw exception.");
		} catch (InterruptedException e) {
		}

		msg = descriptor.evalGroovyExpression(new HashMap<String, String>(),
				"simple message");
		assertEquals("Failure setting message", "simple message", msg);

		System.setProperty("sys_foo", "bar");
		msg = descriptor.evalGroovyExpression(new HashMap<String, String>(),
				"expand sys_foo ${sys['sys_foo']}");
		assertEquals("Failure using system property.", "expand sys_foo bar",
				msg);

		HashMap<String, String> env = new HashMap<String, String>();
		env.put("env_foo", "bar");
		msg = descriptor.evalGroovyExpression(env,
				"expand env_foo ${env['env_foo']}");
		assertEquals("Failure using environment variable.",
				"expand env_foo bar", msg);

		msg = descriptor.evalGroovyExpression(new HashMap<String, String>(),
				"expand ${env['NOT_EXISTING']}");
		assertEquals("Failure using not existing property.", "expand null",
				msg);
	}
}
