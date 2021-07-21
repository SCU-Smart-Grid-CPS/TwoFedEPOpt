/*
File:           controller.java
Project:	EnergyPlusOptOcc2Fed
Author(s):      PJ McCurdy, Kaleb Pattawi, Brian Woo-Shem
Version:        4.7
Last Updated:   2021-07-21
Notes: Code for the optimization simulations. Should compile and run but may not have perfect results.
Run:   Change file paths in this code. Then build or build-all. Run as part of federation.

*Changelog:
    * Added compatibility with occupancyAdaptSetpoints.py
    * Added use config file instead of changing variables in here to reduce recompiling
    * Fixed datastring receiver from Python so that datastrings do not continually append with data for the next datastring
    * Cleaned up code
    * Has variables needed for more frequent optimization calls but does not work because of energyOptTset2hr limitations
    * Fixed logic bug causing Controller to override occupancy setback points and optimization when trying to apply fuzzy
*/

package org.webgme.guest.controller;

import org.webgme.guest.controller.rti.*;

import org.cpswt.config.FederateConfig;
import org.cpswt.config.FederateConfigParser;
import org.cpswt.hla.InteractionRoot;
import org.cpswt.hla.base.AdvanceTimeRequest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// Importing other packages
import java.io.*;
import java.net.*;
import org.cpswt.utils.CpswtUtils;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.Random;    // random num generator
import java.lang.*;
// Added for nice filenames
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

// Define the Controller type of federate for the federation.

public class Controller extends ControllerBase {
    private final static Logger log = LogManager.getLogger();

    private double currentTime = 0;

    public Controller(FederateConfig params) throws Exception {
        super(params);
    }
    
    // Number of optimization to run per hour. Default is 1
    int timestepsToOpt = 12;
    int nopt = 12/timestepsToOpt;

    // Kaleb // defining  global variables
    int numSockets = 1;  // CHANGE FOR MULTIPLE EP SIMS! Default is 1 for single EP.
    String[] varNames = new String[15];   // add more empty vals if sending more vars
    String[] doubles = new String[15];
    String[] dataStrings = new String[numSockets];
    String[] holder=new String[numSockets];
    double[] outTemps=new double[numSockets];
    double[] coolTemps= new double[numSockets]; 
    double[] heatTemps= new double[numSockets];
    double[] heatTempFromOpt= new double[numSockets];
    double[] coolTempFromOpt= new double[numSockets];
    double[] zoneTemps= new double[numSockets];
    double[] zoneRHs= new double[numSockets];
    double[] heatingEnergy= new double[numSockets];
    double[] coolingEnergy= new double[numSockets];
    double[] netEnergy= new double[numSockets];
    double[] energyPurchased= new double[numSockets];
    double[] energySurplus= new double[numSockets];
    double[] solarRadiation= new double[numSockets];
    double[] receivedHeatTemp= new double[numSockets];
    double[] receivedCoolTemp= new double[numSockets];
    double[] dayOfWeek= new double[numSockets];
    double price = 10; // Set a default price here
    int[] numVars = new int[numSockets];
    String[] futureIndoorTemp = new String[timestepsToOpt];
    String[] varsT = new String[timestepsToOpt+2]; 
    String[] varsH = new String[timestepsToOpt+2]; 
    String[] varsC = new String[timestepsToOpt+2];

    String varNameSeparater = "@";
    String doubleSeparater = ",";
    String optDataString = "";
    int day = 0, p = 0;

    boolean receivedSocket = false;
    boolean receivedMarket = false;
    boolean receivedReader = false;
    
    String timestep_Socket = "";
    //String timestep_Reader = "";
    //String timestep_Market = "";
    //String timestep_Controller = "";

    int fuzzy_heat = 0;  // NEEDS TO BE GLOBAL VAR outside of while loop
    int fuzzy_cool = 0;  // NEEDS TO BE GLOBAL VAR outside of while loop\
    
    //Determine Setpoint Type --- Leave these false for config.txt input
    boolean optimizeSet = false; //True if optimized, false if not optimized
    boolean adaptiveSet = false; //True if using adaptive setpoint, false if fixed setpoint. Not used if optimizeSet = true.
    boolean occupancySet = false; //Does it use occupancy?
    
    //Get date + time string for output file naming.
    // Need to do this here because otherwise the date time string might change during the simulation
    String datime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"));

    private void checkReceivedSubscriptions() {
        InteractionRoot interaction = null;
        while ((interaction = getNextInteractionNoWait()) != null) {
            if (interaction instanceof Socket_Controller) {
                handleInteractionClass((Socket_Controller) interaction);
            }
            else {
                log.debug("unhandled interaction: {}", interaction.getClassName());
            }
        }
    }

// Giant method for what Controller does every timestep ____________________________
    private void execute() throws Exception {
        if(super.isLateJoiner()) {
            log.info("turning off time regulation (late joiner)");
            currentTime = super.getLBTS() - super.getLookAhead();
            super.disableTimeRegulation();
        }

        /////////////////////////////////////////////
        // TODO perform basic initialization below //
        /////////////////////////////////////////////
        
        // Read simulation settings from config.txt _____________________________________
        log.info("create bufferedReader");
        File cf = new File("config.txt");
        BufferedReader br = new BufferedReader(new FileReader(cf));
        log.info("bufferedreader successful");
        String st = "";
        String mode = "";
        String heatOrCool = "";
        String dateRange = "";
        while ((st = br.readLine())!=null){
            log.info(st);
            if(st.contains("MODE:")){
                mode = br.readLine();
            }
            else if(st.contains("heatorcool:")){
                heatOrCool = br.readLine();
            }
            else if(st.contains("date_range:")){
                dateRange = br.readLine();
            }
            else if(st.contains("optimize:")){
                optimizeSet = Boolean.parseBoolean(br.readLine());
            }
        }
        log.info("Mode: " + mode);
        log.info("Heat or Cool: " + heatOrCool);
        log.info("Date Range: " + dateRange);
        log.info("Optimize: " + optimizeSet);
        // if not optimizing, figure out occupancySet and adaptiveSet booleans. Note optimize uses MODE, not occupancySet or adaptiveSet.
        if(!optimizeSet){
            if(mode.contains("occupancy")){ occupancySet = true;}
            else if(mode.contains("adaptive")){ adaptiveSet = true;}
        }
        // end config.txt --------------------------------------------------------
        
        AdvanceTimeRequest atr = new AdvanceTimeRequest(currentTime);
        putAdvanceTimeRequest(atr);

        if(!super.isLateJoiner()) {
            log.info("waiting on readyToPopulate...");
            readyToPopulate();
            log.info("...synchronized on readyToPopulate");
        }

        ///////////////////////////////////////////////////////////////////////
        // TODO perform initialization that depends on other federates below //
        ///////////////////////////////////////////////////////////////////////

        if(!super.isLateJoiner()) {
            log.info("waiting on readyToRun...");
            readyToRun();
            log.info("...synchronized on readyToRun");
        }

        startAdvanceTimeThread();
        log.info("started logical time progression");

        while (!exitCondition) {
            atr.requestSyncStart();
            enteredTimeGrantedState();

            ////////////////////////////////////////////////////////////
            // TODO send interactions that must be sent every logical //
            // time step below                                        //
            ////////////////////////////////////////////////////////////

            // Set the interaction's parameters.
            //
            //    Controller_Socket vController_Socket = create_Controller_Socket();
            //    vController_Socket.set_actualLogicalGenerationTime( < YOUR VALUE HERE > );
            //    vController_Socket.set_dataString( < YOUR VALUE HERE > );
            //    vController_Socket.set_federateFilter( < YOUR VALUE HERE > );
            //    vController_Socket.set_originFed( < YOUR VALUE HERE > );
            //    vController_Socket.set_simID( < YOUR VALUE HERE > );
            //    vController_Socket.set_sourceFed( < YOUR VALUE HERE > );
            //    vController_Socket.sendInteraction(getLRC(), currentTime + getLookAhead());

            System.out.println("timestep before receiving Socket/Reader: "+ currentTime);
            log.info("timestep before receiving Socket/Reader: ",currentTime);
            // Waits to receive Socket_Controller and Reader_Controller to ensure everything stays on the same timestep
            while (!(receivedSocket)){
                //while ((!(receivedSocket) || !(receivedReader))){ // Reader stuff is commented out because Reader not currently used
                log.info("waiting to receive Socket_Controller interaction...");
                synchronized(lrc){
                    lrc.tick();
                }
                checkReceivedSubscriptions();
                if(!receivedSocket){
                    CpswtUtils.sleep(100);
                }
            /* }else if(!receivedReader){
                   log.info("waiting on Reader_Controller...");
                   CpswtUtils.sleep(100);
               }*/
            }
          receivedSocket = false;
          receivedReader = false;
          System.out.println("timestep after receiving Socket/Reader and before sending to Market: "+ currentTime);
/*         // Market stuff; commented out because Market is not currently used
           // TODO send Controller_Market here! vvvvvvvv
           log.info("sending Controller_Market interaction");
           Controller_Market sendMarket = create_Controller_Market();
           sendMarket.set_dataString("");
           System.out.println("Send controller_market and Reader_Controller interaction:");
           sendMarket.sendInteraction(getLRC());

          
           log.info("waiting for Market_controller interaction...");
           // Wait to receive price from market  
           while (!receivedMarket){
               log.info("waiting to receive Market_Controller interaction...");
               synchronized(lrc){
                   lrc.tick();
               }
               checkReceivedSubscriptions();
               if(!receivedMarket){
                   log.info("waiting on Market_Controller...");
                   CpswtUtils.sleep(100);
               }
           }
           receivedMarket = false;
           log.info("received Market_controller interaction!");
           System.out.println("timestep after receiving Market: "+ currentTime);
*/

        // OPTIMIZATION _______________________________________________________________________ 
        // Reset variables to defaults
        double hour = (double) ((currentTime%288) / 12);
        log.info("hour is: ",hour);
        System.out.println("hour is:"+hour);
        String s = null;
        String dataStringOpt = "";
        String dataStringOptT = "";
        String dataStringOptP = "";
        String dataStringOptO = "";
        String dataStringOptS = "";
        String dsoHeatSet = "";
        String dsoCoolSet = "";
        String sblock = null;
        String sday = null;
        String separatorOpt = ",";
        char var2save = 'Z'; // default value to save nothing
        String pycmd = "";

        if (optimizeSet || occupancySet){
            // At beginning of the day, increment day
             if (hour == 0){
                 day = day+1;
             }
             // on the whole hours only, run the optimization
             if (hour%nopt == 0){
                 try {
                     sblock= String.valueOf((int)hour);
                     sday = String.valueOf(day);
                     dataStringOpt = sblock;
                     dataStringOptT = sblock;
                     dataStringOptP = sblock;
                     dataStringOptO = sblock;
                     dataStringOptS = sblock;
                     dsoHeatSet = sblock;
                     dsoCoolSet = sblock;
                     System.out.println("sblock: " +sblock);
                     System.out.println("sday: " +sday);
                     System.out.println("zonetemp string: " +String.valueOf(zoneTemps[0]));
                     Process pro;

                    // Call Python optimization & occupancy code with necessary info
                    if (optimizeSet){
                    	 pycmd = "python3 ./energyOptTset2hr.py " +sday +" " +sblock +" "+ String.valueOf(zoneTemps[0])+ " " + String.valueOf(24) + " " + timestepsToOpt + " " + heatOrCool + " " + mode + " " + dateRange;
                        pro = Runtime.getRuntime().exec(pycmd); 
                    }
                    else{ // Call Python adaptive and occupancy setpoints code with necessary info
                    	pycmd = "python3 ./occupancyAdaptSetpoints.py " +sday +" " +sblock +" "+ String.valueOf(zoneTemps[0])+ " " + String.valueOf(24) + " " + timestepsToOpt + " " + heatOrCool + " " + mode + " " + dateRange;
                        pro = Runtime.getRuntime().exec(pycmd); 
                     }
                     System.out.println("Run:  " + pycmd);

                     BufferedReader stdInput = new BufferedReader(new InputStreamReader(pro.getInputStream()));
                
                // Gets input data from Python that will either be a keystring or a variable. 
                // AS long as there is another output line with data,
                     while ((s = stdInput.readLine()) != null) {
                         //System.out.println(s);  //for debug
                         // Changed to a dobule switch-case to reduce computing time and fix so it's not appending data meant for the next one. - Brian
                         // If current line is a keystring, identify it by setting the key var2save to that identity
                         switch (s) {
                            case "energy consumption":
                                 var2save = 'E';
                                 break;
                            case "indoor temp prediction":
                                var2save = 'T';
                                break;
                            case "pricing per timestep":
                                var2save = 'P';
                                break;
                            case "outdoor temp":
                                var2save = 'O';
                                break;
                            case "solar radiation": var2save = 'S'; break;
                            case "heating min": var2save = 'H'; break;
                            case "cooling max": var2save = 'C'; break;
                            default: // Not a keystring, so it is probably data
                            	 switch(var2save) {
                            	 	case 'E': dataStringOpt = dataStringOpt + separatorOpt + s; break;
                            	 	case 'T': dataStringOptT = dataStringOptT + separatorOpt + s; break;
                            	 	case 'P': dataStringOptP = dataStringOptP + separatorOpt + s; break;
                            	 	case 'O': dataStringOptO = dataStringOptO + separatorOpt + s; break;
                            	 	case 'S': dataStringOptS = dataStringOptS + separatorOpt + s; break;
                            	 	case 'H': dsoHeatSet = dsoHeatSet + separatorOpt + s; break;
                            	 	case 'C': dsoCoolSet = dsoCoolSet + separatorOpt + s; break;
                            	 	default: // Do nothing
                                } // End var2save switch case
                            } // End s switch case
                        } //End while next line not null
                     } // End try
                     catch (IOException e) {
                         e.printStackTrace();
                     }
                     //Convert single datastring to array of substrings deliniated by separator
                     String vars[] = dataStringOpt.split(separatorOpt);
                     //System.out.println("vars = " + vars); // Print lines only for debugging
                     varsT = dataStringOptT.split(separatorOpt, timestepsToOpt+1);
                     //System.out.println("varsT = " + varsT);
                     String varsP[] = dataStringOptP.split(separatorOpt);
                     //System.out.println("varsP = " + varsP);
                     String varsO[] = dataStringOptO.split(separatorOpt);
                     //System.out.println("varsO = " + varsO);
                     String varsS[] = dataStringOptS.split(separatorOpt);
                     //System.out.println("varsS = " + varsS);
                     varsH = dsoHeatSet.split(separatorOpt, timestepsToOpt+1);
                     varsC = dsoCoolSet.split(separatorOpt, timestepsToOpt+1);
                     
                     // Get only the needed predicted indoor temp vals. This is clumsy method but works - eventually fix
/*                     for (int in =1;in<13;in++) { //Not needed. Instead, use varsT[i+1] directly
                         futureIndoorTemp[in-1]=varsT[in];
                     } */

                     // Writing data to file _____________________________________________
                     try{
                         // Create new file in Deployment folder with name including description, time and date string - Brian
                         // DataCVXOPT_mode_heatOrCool_YYYY-MM-DD_HH-mm.txt
                         File cvxFile = new File("DataCVXOPT_"+mode+"_"+heatOrCool+"_"+datime+".txt");

                         // If file doesn't exists, then create it
                         if (!cvxFile.exists()) {
                             cvxFile.createNewFile();
                             // Add headers and save them
                             FileWriter fw = new FileWriter(cvxFile.getAbsoluteFile(),true);
                             BufferedWriter bw = new BufferedWriter(fw);
                             bw.write("Energy_Consumption[J]\tIndoor_Temp_Prediction[°C]\tEnergy_Price[$]\tOutdoor_Temp[°C]\tSolarRadiation[W/m^2]\tMin_Heat_Setpt[°C]\tMax_Cool_Setpt[°C]\n");
                             bw.close();
                         }
        
                         FileWriter fw = new FileWriter(cvxFile.getAbsoluteFile(),true);
                         BufferedWriter bw = new BufferedWriter(fw);
        
                         // Write in file
                         for (int in =1;in<13;in++) {
                             bw.write(vars[in]+"\t"+varsT[in]+"\t"+varsP[in]+"\t"+varsO[in]+"\t"+varsS[in]+"\t"+varsH[in]+"\t"+varsC[in]+"\n");
                         }
                         // Close and save file
                         bw.close();
                     }
                     catch(Exception e){
                         System.out.println(e);
                     } // End DataCVXOPT file ------------------------------------------------
        
                    // resetting 
                    var2save = 'Z';
                    //dataStringOpt = "";

                    // Initialize counter for setting setpoint temp for next hour 
                    p=0;
                    System.out.println("timestep p = "+String.valueOf(p));
                } //End hourly optimization
                // Outer loop makes this bit run every timestep that is not on a whole hour

                if(heatOrCool.equals("heat")){
                    heatTemps[0]=Double.parseDouble(varsT[p+1]);
                    heatTempFromOpt[0]=Double.parseDouble(varsT[p+1]);
                    System.out.println("heatTemp: "+String.valueOf(heatTemps[0]));
                    coolTemps[0]=32.0; //Setback to prevent AC activation
                }
                else{ // Cooling
                    coolTemps[0]=Double.parseDouble(varsT[p+1]);
                    coolTempFromOpt[0]=Double.parseDouble(varsT[p+1]);
                    System.out.println("coolTemp: "+String.valueOf(coolTemps[0]));
                    heatTemps[0]=12.0; //Setback to prevent heater activation
                }
                p=p+1;
                System.out.println("timestep p = "+String.valueOf(p));
                
               //-------------------------------------------------------------------------------------------------
               // Now figure out all stuff that needs to be sent to socket...
            
               // determine heating and cooling setpoints for each simID
               // will eventually change this part for transactive energy

             // Fuzzy control 
             double max_cool_temp, min_heat_temp; 
             double OFFSET = 0.6; // need to change slightly higher/lower so E+ doesnt have issues
             for(int i=0;i<numSockets;i++){ // Loop so that it would hypothetically work if we ever add more EP sims at once
                 // Determine minimum and maximum temperatures allowed (we can probably print this from optimization code too)
                 // Instead get it from optimization or occupancy output as 'heating setpoint bounds' and 'cooling setpoint bounds'
                 min_heat_temp = Double.parseDouble(varsH[p+1]);
                 max_cool_temp = Double.parseDouble(varsC[p+1]);

                 // Suspicious that this bit is doing something weird. We already have heatTemps and coolTemps from earlier.
                 // Set these as a band? 
                 //heatTemps[i]=Double.parseDouble(futureIndoorTemp[p-1])-0.5;
                 //coolTemps[i]=Double.parseDouble(futureIndoorTemp[p-1])+0.5;
          /*
                 // Set maximum cool and minimum heats: //WARNING: This could undo the occupancy vacant setback points
                 if (coolTemps[i]>=max_cool_temp){
                     coolTemps[i]=max_cool_temp;
                 }
                 if (heatTemps[i]<=min_heat_temp){
                     heatTemps[i]=min_heat_temp;
                 }
*/
                 // Fuzzy cool and heat are global variables that toggle only when criteria is met.
                 // For Cooling 1 degree under Cooling setpoint:
                 if (heatOrCool.equals("cool")){
                     if (zoneTemps[i] >= max_cool_temp){ // first check if going to exit maximum band
                         fuzzy_cool = -1;
                     } else if (zoneTemps[i] <= coolTemps[i]-1.0){ //colder than necessary, so allow to warm up
                         fuzzy_cool = 1;
                     }
                     coolTemps[i] = coolTemps[i] - 0.6 +fuzzy_cool*OFFSET;   // -0.6 so that oscillates 0-1.2 degree under cooling setpoint
                     heatTemps[i] = 12.0; // IF COOLING for now to avoid turning on heat
                }
                else{ // Heating
                     // For Heating 1 degree under Heating setpoint:
                     if (zoneTemps[i] <= min_heat_temp){ // first check if going to exit minimum band
                         fuzzy_heat = 1;
                     } else if (zoneTemps[i] >= heatTemps[i]+1.0){
                         fuzzy_heat = -1;
                     }
                     heatTemps[i] = heatTemps[i] + 0.6 +fuzzy_heat*OFFSET;  // +0.6 so that oscillates 0-1.2 degree above heating setpoint
                     coolTemps[i] = 32.0; // IF HEATING for now to avoid turning on AC
                }
             } // End fuzzy
//END OPTIMIZATION or OCCUPANCY --------------------------------------------------------------
    } // end giant if to determine if Optimized or occupancy

	else{ // Not optimized and not occupancy

	// Adaptive Setpoint Control: _______________________________________________________________________
	    if (adaptiveSet){
		    // Sets to a the minimum of 18.9 when outdoor temp outTemps < 10C, and max 30.2C when outTemps >= 33.5
		    // Uses sliding scale for 10 < outTemps < 33.5 C
		    // Note if temperature is consistently above 33.5C or below 10C no changes in setpoint will occur.
		    if (outTemps[0]<=10){
		      heatTemps[0]=18.9;
		      coolTemps[0]=22.9;
		    }else if (outTemps[0]>=33.5){
		      heatTemps[0]=26.2;
		      coolTemps[0]=30.2;
		    }else {
		      heatTemps[0] = 0.31*outTemps[0] + 17.8-2+0.5;
		      coolTemps[0] = 0.31*outTemps[0] + 17.8+2+0.5;
		    }

		    if(heatOrCool.equals("heat")){
		        coolTemps[0]= 30.2;     // 23.0
		    }
		    else{
		        heatTemps[0] = 15.0;   // 20.0 
		    }
	    } 
	// End Adaptive Setpoint Control -------------------------------------------------------

	// FIXED SETPOINT _________________________________________________________________________
	    else{ //Not adaptive, so fixed
		    if(heatOrCool.equals("heat")){
		        coolTemps[0] = 30.2;
		        heatTemps[0] = 20.0;
		    }
		    else{ //cool
		         coolTemps[0]= 23.0; //23.0 if cooling, 30.2 if not
		         heatTemps[0] = 15.0; //20.0 if heating, 15.0 if not
		    }
	    }
	//END FIXED SETPT -------------------------------------------------------------------


		//FUZZY CONTROL FOR NO OPTIMIZATION ______________________________________________________
		// Does not activate IF USING OPTIMIZATION
	    for(int i=0;i<numSockets;i++){
		  double OFFSET = 0.6; // need to change slightly higher/lower so E+ doesnt have issues

/* //This is probably garbage because it's redoing what adaptive or fixed setpoints already do. It's impossible for coolTemps or heatTemps
        to ever exceed the max and min cool temp using the non-optimization method.
		  //double max_cool_temp = 30.2; 
		  //double min_heat_temp = 18.9; 
		  // I think if we set these as a band 
		  // heatTemps[i]=Double.parseDouble(futureIndoorTemp[p-1])-0.5;
		  // coolTemps[i]=Double.parseDouble(futureIndoorTemp[p-1])+0.5;

		  // Determine minimum and maximum temperatures allowed (we can probably print this from optimization code too)
		  if (outTemps[i]<=10){
		      min_heat_temp =18.9;
		      max_cool_temp =22.9;
		  }else if (outTemps[i]>=33.5){
		      min_heat_temp =26.2;
		      max_cool_temp =30.2;
		  }else {
		      min_heat_temp = 0.31*outTemps[i] + 17.8-2;
		      max_cool_temp = 0.31*outTemps[i] + 17.8+2;
		  }

		  // Now set maximum cool and minimum heats:
		  if (coolTemps[i]>=max_cool_temp){
		      coolTemps[i]=max_cool_temp;
		      }
		  if (heatTemps[i]<=min_heat_temp){
		      heatTemps[i]=min_heat_temp;
		      }
*/
		if (heatOrCool.equals("cool")){
		  // For Cooling 1 degree under Cooling setpoint:
		  if (zoneTemps[i] >= coolTemps[i]-.1){ // first check if going to exit maximum band
		      fuzzy_cool = -1;
		  } else if (zoneTemps[i] <= coolTemps[i]-1.1){
		      fuzzy_cool = 1;
		  }
		  coolTemps[i] = coolTemps[i] - 0.6 +fuzzy_cool*OFFSET;   // -0.6 so that oscillates 0.1-1.1 degree under cooling setpoint
		  heatTemps[i] = 15.0; // Override for now to avoid turning on AC. 
		}
		else{ //Heat
		  // For Heating 1 degree under Heating setpoint:
		  if (zoneTemps[i] <= heatTemps[i]+.1){ // first check if going to exit minimum band
		      fuzzy_heat = 1;
		  } else if (zoneTemps[i] >= heatTemps[i]+1.1){
		      fuzzy_heat = -1;
		  }
		  heatTemps[i] = heatTemps[i] + 0.6 +fuzzy_heat*OFFSET;  // +0.6 so that oscillates 0.1-1.1 degree above heating setpoint
		  coolTemps[i] = 32.0; // Override for now to avoid turning on heat. 
		  }
      }
    // END FUZZY NO OPT ------------------------------------------------------------

    } // end of giant else to indicate not optimized
    
    System.out.println("heatTemps[0] = "+heatTemps[0] );
    System.out.println("coolTemps[0] = "+coolTemps[0] );

          // Send values to each socket federate
          System.out.println("send to sockets interactions loop");
          for(int i=0;i<numSockets;i++){
            // simID = i;  I am leaving this here to remind myself that i is simID for each socket
            
            dataStrings[i] = "epGetStartCooling"+varNameSeparater;
            dataStrings[i] = dataStrings[i] + String.valueOf(coolTemps[i]) + doubleSeparater;
            
            dataStrings[i] = dataStrings[i] + "epGetStartHeating"+varNameSeparater;
            dataStrings[i] = dataStrings[i] + String.valueOf(heatTemps[i]) + doubleSeparater;
            
            System.out.println("dataStrings[simID] = "+ dataStrings[i] );

            // SendModel vSendModel = create_SendModel();
            // vSendModel.set_dataString(dataString);
            // log.info("Sent sendModel interaction with {}", dataString);
            // vSendModel.sendInteraction(getLRC());

            Controller_Socket sendControls = create_Controller_Socket();
            sendControls.set_dataString(dataStrings[i]);
            sendControls.set_simID(i);
            System.out.println("Send sendControls interaction: " + coolTemps[i] + " to socket #" + i);
            sendControls.sendInteraction(getLRC());
            // SendModel vSendModel = create_SendModel();
            // vSendModel.set_dataString(dataStrings[i]);
            // System.out.println("Send SendModel interaction: " + coolTemps[i] + " to socket #" + i);
            // vSendModel.sendInteraction(getLRC());

            dataStrings[i] = "";
          }

          System.out.println("timestep after sending Socket... should advance after this: "+ currentTime);


            // Writing data to file ______________________________________________________
            try{
                // Create new file
                // New file naming method that goes to Deployment folder and has the time and date string - Brian
                // DataEP_YYYY-MM-DD_HH-mm.txt
                File file = new File("DataEP_"+mode+"_"+heatOrCool+"_"+datime+".txt");

                // If file doesn't exists, then create it
                if (!file.exists()) {
                    file.createNewFile();
                    // Write header row
                    FileWriter fw = new FileWriter(file.getAbsoluteFile(),true);
                    BufferedWriter bw = new BufferedWriter(fw);
                    bw.write("CurrentTime\tHour\tzoneTemps[°C]\toutTemps[°C]\tsolarRadiation[W/m^2]\treceivedHeatTemp[°C]\treceivedCoolTemp[°C]\tcoolTempFromOpt[°C]\theatTemps[°C]\tcoolTemps[°C]\n");
                }

                FileWriter fw = new FileWriter(file.getAbsoluteFile(),true);
                BufferedWriter bw = new BufferedWriter(fw);

                // Write in file
                bw.write(currentTime+"\t"+hour+"\t"+ zoneTemps[0]+"\t"+ outTemps[0]+"\t"+ solarRadiation[0]+"\t" + receivedHeatTemp[0]+"\t"+ receivedCoolTemp[0]+"\t"+coolTempFromOpt[0]+"\t" +heatTemps[0]+"\t"+coolTemps[0]+"\n");
               
                // Close connection & save file
                bw.close();
            }
            catch(Exception e){
                System.out.println(e);
            }

            ////////////////////////////////////////////////////////////////////
            // TODO break here if ready to resign and break out of while loop //
            ////////////////////////////////////////////////////////////////////

            if (!exitCondition) {
                currentTime += super.getStepSize();
                AdvanceTimeRequest newATR =
                    new AdvanceTimeRequest(currentTime);
                putAdvanceTimeRequest(newATR);
                atr.requestSyncEnd();
                atr = newATR;
            }
        }

        // call exitGracefully to shut down federate
        exitGracefully();

        //////////////////////////////////////////////////////////////////////
        // TODO Perform whatever cleanups are needed before exiting the app //
        //////////////////////////////////////////////////////////////////////
    }

// This method is what controller does with the data sent from Socket.java
    private void handleInteractionClass(Socket_Controller interaction) {
        ///////////////////////////////////////////////////////////////
        // TODO implement how to handle reception of the interaction //
        ///////////////////////////////////////////////////////////////

        // can now exit while loop waiting for this interaction
        log.info("received RCModel_Controller interaction");
        receivedSocket = true;

        // Could make global var that holds simIDs but it would just be 0,1,2,...
        // int simID = 0;
        int simID = interaction.get_simID();
        System.out.println("numVars[simID] = " + numVars[simID]);
        holder[simID] = interaction.get_dataString();
        System.out.println("holder[simID] = "+ holder[simID] );

        System.out.println("handle interaction loop");

        String vars[] = holder[simID].split(doubleSeparater);
        System.out.println("vars[0] = "+vars[0]);
        System.out.println("length of vars = " + vars.length);
        int j=0;
        for( String token : vars){
          System.out.println("token = " +token);
          String token1[] = token.split(varNameSeparater);
          System.out.println("token1[0] = "+token1[0]);
          System.out.println("token1[1] = "+token1[1]);
          varNames[j] = token1[0];
          doubles[j] = token1[1];
          System.out.println("varNames[j] = "+ varNames[j] );
          System.out.println("doubles[j] = "+ doubles[j] );
          j = j+1;
        }

        // organize varNames and doubles into vectors of values
        for(int i=0; i<j;i++){
          if(varNames[i].equals("epSendZoneMeanAirTemp")){
            zoneTemps[simID] = Double.valueOf(doubles[i]);
          }
          else if(varNames[i].equals("epSendOutdoorAirTemp")){
            outTemps[simID] = Double.valueOf(doubles[i]);
          }
          else if(varNames[i].equals("epSendZoneHumidity")){
            zoneRHs[simID] = Double.valueOf(doubles[i]);
          }
          else if(varNames[i].equals("epSendHeatingEnergy")){
            heatingEnergy[simID] = Double.valueOf(doubles[i]);
          }
          else if(varNames[i].equals("epSendCoolingEnergy")){
            coolingEnergy[simID] = Double.valueOf(doubles[i]);
          }
          else if(varNames[i].equals("epSendNetEnergy")){
            netEnergy[simID] = Double.valueOf(doubles[i]);
          }
          else if(varNames[i].equals("epSendEnergyPurchased")){
            energyPurchased[simID] = Double.valueOf(doubles[i]);
          }
          else if(varNames[i].equals("epSendEnergySurplus")){
            energySurplus[simID] = Double.valueOf(doubles[i]);
          }
          else if(varNames[i].equals("epSendDayOfWeek")){
            dayOfWeek[simID] = Double.valueOf(doubles[i]);
          }
          else if(varNames[i].equals("epSendSolarRadiation")){
            solarRadiation[simID] = Double.valueOf(doubles[i]);
          }
          else if(varNames[i].equals("epSendHeatingSetpoint")){
            receivedHeatTemp[simID] = Double.valueOf(doubles[i]);
          }
          else if(varNames[i].equals("epSendCoolingSetpoint")){
            receivedCoolTemp[simID] = Double.valueOf(doubles[i]);
          }
          else if(varNames[i].equals("price")){
            price = Double.valueOf(doubles[i]);
          }
          // checking timesteps:
          else if(varNames[i].equals("timestep")){
            timestep_Socket = doubles[i];
          }
        }
      
    }

// Automatically created method; don't change without a good reason
    public static void main(String[] args) {
        try {
            FederateConfigParser federateConfigParser =
                new FederateConfigParser();
            FederateConfig federateConfig =
                federateConfigParser.parseArgs(args, FederateConfig.class);
            Controller federate =
                new Controller(federateConfig);
            federate.execute();
            log.info("Done.");
            System.exit(0);
        }
        catch (Exception e) {
            log.error(e);
            System.exit(1);
        }
    }
}
