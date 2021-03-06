package org.bonitasoft.forklift;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bonitasoft.forklift.ForkliftAPI.BonitaAccessor;
import org.bonitasoft.forklift.ForkliftAPI.ConfigurationSet;
import org.bonitasoft.forklift.ForkliftAPI.ResultSynchronization;
import org.bonitasoft.forklift.artefact.Artefact;
import org.bonitasoft.forklift.artefact.Artefact.Action;
import org.bonitasoft.forklift.artefact.Artefact.DeployOperation;
import org.bonitasoft.forklift.artefact.Artefact.DeploymentStatus;
import org.bonitasoft.forklift.artefact.Artefact.DetectionStatus;
import org.bonitasoft.forklift.artefact.ArtefactBDM;
import org.bonitasoft.forklift.artefact.ArtefactLayout;
import org.bonitasoft.forklift.artefact.ArtefactLivingApplication;
import org.bonitasoft.forklift.artefact.ArtefactLookAndFeel;
import org.bonitasoft.forklift.artefact.ArtefactOrganization;
import org.bonitasoft.forklift.artefact.ArtefactPage;
import org.bonitasoft.forklift.artefact.ArtefactProcess;
import org.bonitasoft.forklift.artefact.ArtefactProfile;
import org.bonitasoft.forklift.artefact.ArtefactRestApi;
import org.bonitasoft.forklift.artefact.ArtefactTheme;
import org.bonitasoft.forklift.source.Source;
import org.bonitasoft.log.event.BEvent;
import org.bonitasoft.log.event.BEvent.Level;
import org.bonitasoft.log.event.BEventFactory;

public class Synchronize {

	private static BEvent EventDeploymentFailed = new BEvent(Synchronize.class.getName(), 1, Level.ERROR, "Error during Deployment", "An error occures at the deploiment", "Deployment can done partialy", "Check the exception");

	private ConfigurationSet configurationSet;

	public void setConfiguration(ConfigurationSet configurationSet) {
		this.configurationSet = configurationSet;
	}

	/**
	 * 
	 * @param bonitaAccessor
	 * @return
	 */
	public ResultSynchronization detect(BonitaAccessor bonitaAccessor) {
		ResultSynchronization resultSynchronization = new ResultSynchronization();
		Date dateBegin = new Date();
		resultSynchronization.addReport("Detection start at "+ForkliftAPI.sdf.format( dateBegin));
		
		Map<String,Long> numberPerContent = new HashMap<String,Long>();
		
		// run each source
		for (Source source : configurationSet.listSources) {
			resultSynchronization.addReport("Source " + source.getName());
			List<Artefact> listArtefact = source.getListArtefactDetected();
			
			orderArtefacts( listArtefact);

			
			for (Artefact artefact : listArtefact) {
				// if the configuration allow this artefact ?
				if (configurationSet.isContentAllow(artefact)) {
					Long nb = numberPerContent.get(artefact.getTypeId());
					numberPerContent.put(artefact.getTypeId(), nb==null ? 1L : nb+1);
					
					resultSynchronization.addReport("  Deploy " + artefact.getTypeId() + " " + artefact.getName());
					DeployOperation deployOperation = artefact.detectDeployment(bonitaAccessor);
					if (deployOperation.detectionStatus==null)
					{
						// create one analysis
						if (deployOperation.presentDateArtefact==null)
						{
							deployOperation.detectionStatus= DetectionStatus.NEWARTEFAC;
							deployOperation.report="The artefact is new, deploy this version";
						}
						else if (deployOperation.presentDateArtefact!=null && deployOperation.presentDateArtefact.equals(artefact.getDate()))
						{
							deployOperation.detectionStatus= DetectionStatus.SAME;
							deployOperation.report="A version exist with the same date ("+ForkliftAPI.sdf.format( artefact.getDate())+")";
						}
						else if ( deployOperation.presentDateArtefact.before(artefact.getDate()))
						{
							deployOperation.detectionStatus= DetectionStatus.NEWVERSION;
							deployOperation.report="The version is new";
						}
						else
						{
							deployOperation.detectionStatus= DetectionStatus.OLDVERSION;
							deployOperation.report="The version is older, you should ignore this one";
						}
					}
					resultSynchronization.addErrorsEvent(deployOperation.listEvents);
					resultSynchronization.addReport(deployOperation.report);
					deployOperation.artefact = artefact; // to be sure
					
					// calculate the appropriate decision
					switch (deployOperation.detectionStatus)
					{
					case NEWARTEFAC:
					case NEWVERSION:
						deployOperation.action= Action.DEPLOY;
						break;
					case OLDVERSION:
					case SAME:
						deployOperation.action= Action.DELETE;
						break;
					case DETECTIONFAILED:
						deployOperation.action= Action.IGNORE;
						break;
					}
					
					resultSynchronization.addDetection( deployOperation );
				}
			}
		}
		
		// report of the analysis
		boolean isNothingIsAllow=true;
		for (String typeId : configurationSet.getContentAllow().keySet())
		{
			if (Boolean.TRUE.equals(configurationSet.getContentAllow().get( typeId )))
			{
				Long nb = numberPerContent.get(typeId);
				
				resultSynchronization.addReport( typeId+" ..."+ (nb==null ? "0": nb));
				isNothingIsAllow=false;
			}
		}
		if (isNothingIsAllow)
			resultSynchronization.addReport( "No content detection: check the configuration to detect one item as minimum");
		return resultSynchronization;

	}

	/**
	 * Play the automatic synchronization now !
	 * 
	 * @param processAPI
	 * @param identityAPI
	 * @return
	 */
	
	public ResultSynchronization start(BonitaAccessor bonitaAccessor) {
		ResultSynchronization resultSynchronization = new ResultSynchronization();
		Date dateBegin = new Date();
		int countDetectArtefact=0;
		
		int countDeployArtefactWithSuccess=0;
		int countDeployArtefactFailed=0;
		int countDeployArtefactIgnored=0;
		try
		{
		resultSynchronization.addReport("Synchronisation start at "+ForkliftAPI.sdf.format( dateBegin));
		

		// run each source
		for (Source source : configurationSet.listSources) {
			resultSynchronization.addReport("Source " + source.getName());
			List<Artefact> listArtefact = source.getListArtefactDetected();
			
			orderArtefacts( listArtefact);

			
			for (Artefact artefact : listArtefact) {
				countDetectArtefact++;
				
				// is this artefact is part of the listAction ?
				boolean allowDeployment=false;
				boolean askSourceToRemoveArtefact=false;
				if (configurationSet.listActions!=null)
				{
					for (Map<String,Object> actionMap : configurationSet.listActions)
					{
						if (isEquals(artefact.getTypeId(), actionMap.get("type") )
							&& isEquals(artefact.getName(), actionMap.get("name")) 
							&& isEquals(artefact.getVersion(), actionMap.get("version")))
						{
							// we get the action to do
							if (Artefact.Action.DEPLOY.toString().equals(actionMap.get("action")))
							{
								allowDeployment=true;
								askSourceToRemoveArtefact=true;
							}
							if (Artefact.Action.DELETE.toString().equals(actionMap.get("action")))
								askSourceToRemoveArtefact=true;
							
						}
					}
				}
				else
					allowDeployment= configurationSet.isContentAllow(artefact);
				
				DeployOperation deployOperation=null;
				
				// if the configuration allow this artefact ?
				if (allowDeployment) {
					String logDeployment=artefact.getTypeId() + " " + artefact.getName()+" : ";
					// load it ?
					boolean continuueOperation=true;
					if (! artefact.isLoaded)
					{
						logDeployment+="loaded,";
						List<BEvent> listEvents = artefact.sourceOrigin.loadArtefact(artefact);
						if (BEventFactory.isError(listEvents)) {
							deployOperation= new DeployOperation();
							deployOperation.artefact=artefact;
							deployOperation.listEvents= listEvents;
							deployOperation.deploymentStatus = DeploymentStatus.LOADFAILED;
							logDeployment+=" failed.";
							continuueOperation=false;
						}
					}
					if (continuueOperation)
					{
						logDeployment+="Deploy ";
						deployOperation = artefact.deploy(bonitaAccessor);
						deployOperation.artefact=artefact;
					}
					logDeployment+= deployOperation.report;
					// decision can be updated now
					askSourceToRemoveArtefact = false;

					switch (deployOperation.deploymentStatus) {
					case REMOVEFAIL:
						logDeployment += "ERROR: Remove current failed";
						countDeployArtefactFailed++;
						break;
					case NOTHINGDONE:
						logDeployment += "INFO: Nothing is done";
						countDeployArtefactFailed++;
						break;
					case NEWALREADYINPLACE:
						logDeployment += "INFO: A new artefact is already in place";
						askSourceToRemoveArtefact = true;
						countDeployArtefactIgnored++;
						break;
					case LOADFAILED:
						logDeployment += "ERROR: Load the content of the artefact failed";
						countDeployArtefactFailed++;
						break;
					case DEPLOYEDFAILED:
						logDeployment += "ERROR: Deployment failed";
						countDeployArtefactFailed++;
						break;
					case DEPLOYED:
						logDeployment += "SUCCESS: Deployment is done";
						askSourceToRemoveArtefact = true;
						countDeployArtefactWithSuccess++;
						break;
					}

					resultSynchronization.addErrorsEvent(deployOperation.listEvents);
					resultSynchronization.addReport(logDeployment );
				}
				if (askSourceToRemoveArtefact)
				{
					if (deployOperation==null)
					{
						// If not exist ==> THats mean only a delete was requested
						deployOperation = new DeployOperation();
						deployOperation.artefact=artefact;
					}
					List<BEvent> listEvents=source.removeArtefact(artefact);
					if (BEventFactory.isError(listEvents))
						resultSynchronization.addErrorsEvent(listEvents);
					if (deployOperation.deploymentStatus==null)
						deployOperation.deploymentStatus=DeploymentStatus.DELETED;
				}
				if (deployOperation!=null)
					resultSynchronization.addDetection( deployOperation);
			
			
			} // end listArtefact
		} // end source
		}catch(Exception e)
		{
			resultSynchronization.addErrorEvent( new BEvent(EventDeploymentFailed, e, ""));
		}
		Date dateEnd = new Date();		
		resultSynchronization.addReport("Synchronisation End at "+ForkliftAPI.sdf.format( dateEnd )+" in "+( dateEnd.getTime() - dateBegin.getTime())+" ms");
		resultSynchronization.addReport("  Detected artefacts      : "+countDetectArtefact);
		resultSynchronization.addReport("  Deployment with success : "+countDeployArtefactWithSuccess);
		resultSynchronization.addReport("  Deployment failed       : "+countDeployArtefactFailed);
		resultSynchronization.addReport("  Deployment ignored      : "+countDeployArtefactIgnored);

		resultSynchronization.addReport("Synchronisation End at "+ForkliftAPI.sdf.format( new Date())+" in ");

		return resultSynchronization;
	}
	
	private boolean isEquals( String o1, Object o2)
	{
		if (o1==null && o2==null)
			return true;
		if (o1!=null && o2!=null)
			return o1.equals(o2);
		return false;
	}
	
	private void orderArtefacts(List<Artefact> listArtefact)
	{
		final List<Class> listOrder = new ArrayList<Class>();
		listOrder.add(ArtefactLayout.class);
		listOrder.add(ArtefactTheme.class);
		listOrder.add(ArtefactLookAndFeel.class);

		listOrder.add(ArtefactBDM.class);

		listOrder.add(ArtefactOrganization.class);
		listOrder.add(ArtefactRestApi.class);
		listOrder.add(ArtefactPage.class);
		listOrder.add(ArtefactProcess.class);
		listOrder.add(ArtefactProfile.class);
		
		listOrder.add(ArtefactLivingApplication.class);
		
		// Attention, deployment must be done in a certain order
		 Collections.sort(listArtefact, new Comparator<Artefact>()
		    {
		      public int compare(Artefact s1,
		    		  Artefact s2)
		      {
		    	  int rangeS1=0;
		    	  int rangeS2=0;
		    	  
		    	  for (int i=0;i<listOrder.size();i++)
		    	  {
		    		  if (listOrder.get(i).equals(s1.getClass()))
		    			  rangeS1=i;
		    		  if (listOrder.get(i).equals(s2.getClass()))
		    			  rangeS2=i;
		    	  }
		    	  if (rangeS1 != rangeS2)
		    		  return Integer.valueOf( rangeS1 ).compareTo( rangeS2 );
		    	  return s1.getName().compareTo(s2.getName());
		      }
		    });
	}

}
