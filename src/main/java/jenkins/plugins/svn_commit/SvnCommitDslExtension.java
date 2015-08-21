package jenkins.plugins.svn_commit;

import hudson.Extension;
import javaposse.jobdsl.dsl.helpers.publisher.PublisherContext;
import javaposse.jobdsl.plugin.ContextExtensionPoint;
import javaposse.jobdsl.plugin.DslExtensionMethod;

@Extension(optional = true)
public class SvnCommitDslExtension extends ContextExtensionPoint {
	@DslExtensionMethod(context = PublisherContext.class)
	public Object svncommit(String commitComment) {
		return new SvnCommitPublisher(commitComment);
	}
}
