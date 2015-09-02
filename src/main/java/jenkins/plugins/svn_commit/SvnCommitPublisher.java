package jenkins.plugins.svn_commit;

import java.io.IOException;
import java.util.HashMap;

import org.codehaus.groovy.control.CompilationFailedException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import net.sf.json.JSONObject;

public class SvnCommitPublisher extends Notifier {

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
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener) throws InterruptedException, IOException {
		return SvnCommitPlugin.perform(build, launcher, listener,
				this.commitComment, this.includeIgnored);
	}

	@Override
	public SvnCommitDescriptorImpl getDescriptor() {
		return (SvnCommitDescriptorImpl) super.getDescriptor();
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
				SvnCommitPlugin.evalGroovyExpression(
						new HashMap<String, String>(), value);
				return FormValidation.ok();
			} catch (CompilationFailedException e) {
				return FormValidation.error(Messages.BadGroovy(e.getMessage()));
			}
		}
	}
}
