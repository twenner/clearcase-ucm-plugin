package net.praqma.hudson.remoting;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.logging.Logger;

import net.praqma.clearcase.exceptions.ClearCaseException;
import net.praqma.clearcase.exceptions.RebaseException;
import net.praqma.clearcase.ucm.entities.Baseline;
import net.praqma.clearcase.PVob;
import net.praqma.clearcase.Rebase;
import net.praqma.clearcase.ucm.entities.Activity;
import net.praqma.clearcase.ucm.entities.Stream;
import net.praqma.clearcase.ucm.entities.Version;
import net.praqma.clearcase.ucm.view.SnapshotView;
import net.praqma.clearcase.ucm.view.SnapshotView.Components;
import net.praqma.clearcase.ucm.view.SnapshotView.LoadRules;
import net.praqma.clearcase.util.ExceptionUtils;
import net.praqma.hudson.*;
import net.praqma.hudson.exception.ScmException;
import net.praqma.hudson.exception.UnableToInitializeWorkspaceException;
import net.praqma.hudson.scm.ClearCaseChangeset;

import hudson.FilePath.FileCallable;
import hudson.model.BuildListener;
import hudson.remoting.VirtualChannel;

public class CheckoutTask implements FileCallable<EstablishResult> {

	private static final long serialVersionUID = -7029877626574728221L;
	private PrintStream hudsonOut;
	private String jobname;
	private SnapshotView sv;
	private String loadModule;
	private Baseline bl;
	private String buildProject;
	private Stream targetStream;
	private BuildListener listener;
	private Integer jobNumber;
	private String id = "";
	
	private boolean any = false;

    private Logger logger;

	public CheckoutTask( BuildListener listener, String jobname, Integer jobNumber, Stream targetStream, String loadModule, Baseline baseline, String buildProject, boolean any ) {
		this.jobname = jobname;
		this.jobNumber = jobNumber;
		this.targetStream = targetStream;
		this.loadModule = loadModule;
		this.bl = baseline;
		this.buildProject = buildProject;
		this.listener = listener;
		
		this.any = any;

		this.id = "[" + jobname + "::" + jobNumber + "-cotask]";
	}

	@Override
	public EstablishResult invoke( File workspace, VirtualChannel channel ) throws IOException {

        logger = Logger.getLogger( CheckoutTask.class.getName() );

		hudsonOut = listener.getLogger();
		
		logger.fine( id + "Starting CheckoutTask" );

		String diff = "";
		String viewtag = makeViewtag();
		
		EstablishResult er = new EstablishResult();
		ClearCaseChangeset changeset = new ClearCaseChangeset();
		
		/* We need to load target stream */
		try {
			targetStream.load();
		} catch( Exception e1 ) {
			ExceptionUtils.print( e1, hudsonOut, false );
			ExceptionUtils.log( e1, true );
			
			throw new IOException( e1 );
		}

		try {
			logger.fine( id + "Getting dev stream" );
			Stream devstream = getDeveloperStream( "stream:" + viewtag, targetStream.getPVob() );
			devstream.load();
			
			logger.fine( id + "Getting foundation baseline" );
			Baseline foundation = devstream.getFoundationBaseline();
			foundation.load();
			
			if( !foundation.getStream().equals( targetStream ) ) {
				hudsonOut.println( "[" + Config.nameShort + "] The foundation baseline " + foundation.getShortname() + " does not match the stream " + targetStream.getShortname() + ". Changelog will probably be bogus." );
			}
			
			logger.fine( id + "Making workspace" );
			
			makeWorkspace( workspace, viewtag );
			List<Activity> bldiff = null;
			if( any ) {
				if( devstream.isCreated() ) {
					logger.fine( "Diffing newly created stream" );
					bldiff = Version.getBaselineDiff( targetStream.getFoundationBaseline(), bl, true, sv.getViewRoot() );
				} else {
					logger.fine( "Diffing OOOOld stream" );
					bldiff = Version.getBaselineDiff( foundation, bl, true, sv.getViewRoot() );
				}
			} else {
				//bldiff = bl.getDifferences( sv );
				bldiff = Version.getBaselineDiff( bl, null, true, sv.getViewRoot() );
			}
			
			logger.fine( id + "Creating change log" );
			//List<Activity> bldiff = Version.getBaselineDiff( bl, null, true, sv.getViewRoot() );
			diff = Util.createChangelog( bldiff, bl );
			hudsonOut.print( "[" + Config.nameShort + "] Found " + bldiff.size() + " activit" + ( bldiff.size() == 1 ? "y" : "ies" ) + ". " );
			int c = 0;
			for( Activity a : bldiff ) {
				c += a.changeset.versions.size();
				for( Version version : a.changeset.versions ) {
					changeset.addChange( version.getFullyQualifiedName(), version.getUser() );
				}
			}
			
			logger.fine( id + "DONE" );
			hudsonOut.println( c + " version" + ( c == 1 ? "" : "s" ) + " involved" );
			
			logger.info( "CheckoutTask finished normally" );
			
		} catch( Exception e ) {
			throw new IOException( "", new UnableToInitializeWorkspaceException( "Unable to initialize workspace", e ) );			
		}

		er.setMessage( diff );
		er.setViewtag( viewtag );
		er.setChangeset( changeset );
		
		return er;
	}
	
	private String makeViewtag() {
		String newJobName = jobname.replaceAll("\\s", "_");
		String viewtag = "CCUCM_" + newJobName + "_" + System.getenv("COMPUTERNAME");
		
		return viewtag;
	}

	private void makeWorkspace( File workspace, String viewtag ) throws ScmException, ClearCaseException {
		// We know we have a stream (st), because it is set in
		// baselinesToBuild()
		if( workspace != null ) {
			logger.fine( id + "workspace: " + workspace.getAbsolutePath() );
		} else {
			logger.fine( id + "workspace must be null???" );
		}

		File viewroot = new File( workspace, "view" );
		
		logger.fine( id + "Creating dev strem" );
		Stream devstream = getDeveloperStream( "stream:" + viewtag, targetStream.getPVob() );

		logger.fine( id + "Making view" );
		sv = Util.makeView( devstream, workspace, listener, loadModule, viewroot, viewtag, false );
		

		// Now we have to rebase - if a rebase is in progress, the
		// old one must be stopped and the new started instead
		logger.fine( id + "Checking rebasing" );
		if( Rebase.isInProgress( devstream ) ) {
			hudsonOut.print( "[" + Config.nameShort + "] Cancelling previous rebase." );
			Rebase.cancelRebase( devstream );
			hudsonOut.println( " Done" );
		}
		// The last boolean, complete, must always be true from CCUCM
		// as we are always working on a read-only stream according
		// to LAK
		hudsonOut.print( "[" + Config.nameShort + "] Rebasing development stream (" + devstream.getShortname() + ") against parent stream (" + targetStream.getShortname() + ")" );
		try {
			logger.fine( id + "Rebasing" );
			Rebase rebase = new Rebase( devstream, sv, bl );
			rebase.rebase( true );
			logger.fine( id + "Rebasing done" );
		} catch( RebaseException e1 ) {
			logger.fine( id + "Rebasing failed: " + e1.getMessage() );
			hudsonOut.println( " Failed" );
			throw e1;
		}
		
		hudsonOut.println( " Done" );
		
		try {
            hudsonOut.println("[" + Config.nameShort + "] Updating view using " + loadModule.toLowerCase() + " modules");
            logger.fine( id + "Updating stream" );
            //sv.Update(true, true, true, false, Components.valueOf(loadModule.toUpperCase()), null);
            sv.Update(true, true, true, false, new LoadRules( sv, Components.valueOf(loadModule.toUpperCase()) ));
            logger.fine( id + "Updating done" );
        } catch (ClearCaseException e) {
            e.print( hudsonOut );
            throw new ScmException("Could not update snapshot view", e );
        }
	}

	private Stream getDeveloperStream( String streamname, PVob pvob ) throws ScmException {
		Stream devstream = null;

		try {
			if( Stream.streamExists( streamname + "@" + pvob ) ) {
				devstream = Stream.get( streamname, pvob );
			} else {
				if( buildProject.equals( "" ) ) {
					buildProject = null;
				}
				devstream = Stream.create( Config.getIntegrationStream( bl, buildProject ), streamname + "@" + pvob, true, bl );
			}
		}
		/*
		 * This tries to handle the issue where the project hudson is not
		 * available
		 */
		catch (ScmException se) {
			throw se;

		} catch (Exception e) {
			throw new ScmException( "Could not get stream", e );
		}

		return devstream;
	}

	public SnapshotView getSnapshotView() {
		return sv;
	}

}
