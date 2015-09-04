package jenkins.plugins.svn_commit;

import hudson.Extension;
import javaposse.jobdsl.dsl.Context;
import javaposse.jobdsl.dsl.helpers.publisher.PublisherContext;
import javaposse.jobdsl.plugin.ContextExtensionPoint;
import javaposse.jobdsl.plugin.DslExtensionMethod;

@Extension(optional = true)
public class SvnCommitDslExtension extends ContextExtensionPoint {
	@DslExtensionMethod(context = PublisherContext.class)
	public Object svncommit(String commitComment, boolean includeIgnored) {
		return new SvnCommitPublisher(commitComment, includeIgnored);
	}

	@DslExtensionMethod(context = PublisherContext.class)
	public Object svncommit(Runnable closure) {
		SvnCommitContext context = new SvnCommitContext();
		executeInContext(closure, context);
		return new SvnCommitPublisher(context.commitComment,
				context.includeIgnored);
	}

	public class SvnCommitContext implements Context {
		String commitComment;
		boolean includeIgnored = false;

		public void commitComment(String value) {
			commitComment = value;
		}

		public void includeIgnored(boolean value) {
			includeIgnored = value;
		}
	}

}