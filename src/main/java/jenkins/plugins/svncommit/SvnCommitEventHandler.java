package jenkins.plugins.svncommit;

import java.io.File;
import java.io.PrintStream;

import org.tmatesoft.svn.core.SVNCancelException;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.wc.SVNEvent;
import org.tmatesoft.svn.core.wc.SVNEventAction;

import hudson.scm.SubversionEventHandlerImpl;

/**
 * Just prints out the commit completed message.
 * 
 * @author Christian Haug
 */
public class SvnCommitEventHandler extends SubversionEventHandlerImpl {

	public SvnCommitEventHandler(PrintStream out, File baseDir) {
		super(out, baseDir);
	}

	@Override
	public void handleEvent(SVNEvent event, double progress)
			throws SVNException {
		SVNEventAction action = event.getAction();
		if (action == SVNEventAction.COMMIT_COMPLETED) {
			out.println(Messages.Commited(event.getRevision()));
		}
		super.handleEvent(event, progress);
	}

	public void checkCancelled() throws SVNCancelException {
		if (Thread.interrupted())
			throw new SVNCancelException();
	}
}
