/*
File:           controller.java
Project:	EnergyPlusOptOcc2Fed
Author(s):      PJ McCurdy, Kaleb Pattawi, Brian Woo-Shem
Version:        5.0
Last Updated:   2021-07-26
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
// Default package imports
import org.webgme.guest.controller.rti.*;
import org.cpswt.config.FederateConfig;
import org.cpswt.config.FederateConfigParser;
import org.cpswt.hla.InteractionRoot;
import org.cpswt.hla.base.AdvanceTimeRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// Added package imports
import java.io.*;
import java.net.*;
import org.cpswt.utils.CpswtUtils;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.Random;    // random num generator
import java.lang.*;
// For nice date labeled filenames
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

    // defining  global variables
    int numSockets = 1;  // CHANGE FOR MULTIPLE EP SIMS! Default is 1 for single EP.
    String[] varNames = new String[15];   // add more empty vals if sending more vars
    String[] doubles = new String[15];
    String[] dataStrings = new String[numSockets];
    String[] holder=new String[numSockets];
    double[] outTemps=new double[numSockets]; //Outdoor temp
    double[] coolTemps= new double[numSockets]; 
    double[] heatTemps= new double[numSockets];
    // From EP via Socket
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
    int[] numVars = new int[numSockets]; //for multiple sockets
    // Make these keystrings global variables to be accessed outside of their original loop
    String[] varsT = new String[timestepsToOpt+2]; 
    String[] varsH = new String[timestepsToOpt+2]; 
    String[] varsC = new String[timestepsToOpt+2];
    // keystrings for encoding and decoding data into a string
    String varNameSeparator = "@";
    String doubleSeparator = ",";
    String optDataString = "";
    int day = 0, p = 0;
    // for no time delay
    boolean receivedSocket = false;
    boolean receivedMarket = false;
    boolean receivedReader = false;
    int waitTime = 0;
    
    String timestep_Socket = "";
    //String timestep_Reader = "";
    //String timestep_Market = "";
    //String timestep_Controller = "";

    int fuzzy_heat = 0;  // NEEDS TO BE GLOBAL VAR outside of while loop
    int fuzzy_cool = 0;  // NEEDS TO BE GLOBAL VAR outside of while loop
    
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
        log.info("Getting Configuration Settings: ");
        File cf = new File("config.txt");
        BufferedReader br = new BufferedReader(new FileReader(cf));
        String st = "";
        String mode = "";
        String heatOrCool = "";
        String dateRange = "";
        while ((st = br.readLine())!=null){
            log.info(st);
            if(st.contains("MODE:")){ //Use contains so tagline can have other instructions
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
        // if not optimizing, figure out occupancySet and adaptiveSet booleans. Note optimize uses only MODE, not occupancySet or adaptiveSet.
        if(!optimizeSet){
            if(mode.contains("occupancy")){ occupancySet = true;}
            else if(mode.contains("adaptive")){ adaptiveSet = true;}
            else if(mode.equals("")){ System.out.println("Text Alert: config.txt missing or contains invalid parameters."); }
        }
        // end config.txt --------------------------------------------------------
        
        AdvanceTimeRequest atr = new AdvanceTimeRequest(currentTime);
        putAdvanceTimeRequest(atr);

        if(!super.isLateJoiner()) {
            log.info("waiting on readyToPopulate...");
            try{
                readyToPopulate();
            }
            catch (Exception ej){
                System.out.println("Data Explosion! Please reboot your computer and try again.");
            }
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
                    CpswtUtils.sleep(100 + waitTime);
                    waitTime++;
                    if(waitTime > 500){
                        System.out.println("Socket has abandoned me! I'm tired of waiting. Goodbye.");
                        System.exit(1);
                    }
                }
            /* }else if(!receivedReader){
                   log.info("waiting on Reader_Controller...");
                   CpswtUtils.sleep(100);
               }*/
            }
          receivedSocket = false;
          waitTime = 0;
          //receivedReader = false;
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
                     System.out.println("Run:  " + pycmd); //Display command used for debugging

                     BufferedReader stdInput = new BufferedReader(new InputStreamReader(pro.getInputStream()));
                
                // Gets input data from Python that will either be a keystring or a variable. 
                // AS long as there is another output line with data,
                     while ((s = stdInput.readLine()) != null) {
                         //System.out.println(s);  //for debug
                         // New nested switch-case to reduce computing time and fix so it's not appending data meant for the next one. - Brian
                         // Replaced a bunch of booleans with single key char var2save - Brian
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
                            case "solar radiation": 
                                var2save = 'S'; 
                                break;
                            case "heating min": 
                                var2save = 'H'; 
                                break;
                            case "cooling max": 
                                var2save = 'C'; 
                                break;
                            case "Traceback (most recent call last):":
                                System.out.println("\nHiss... Python crash detected. Try pasting command after \"Run\" in the terminal and debug Python.");
                                break;
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
                         System.out.println("\nHiss... Python crashed or failed to run. Try pasting command after \"Run\" in the terminal and debug Python."); 
                     }
                     // Extra check if no keystrings found, var2save will still be default 'Z'. Controller will probably crash after this, but it is usually caused by Python code crashing and not returning anything. Warn user so they debug correct program.
                     if (var2save == 'Z') { System.out.println("Hiss... No keystrings from Python found. Python may have crashed and returned null. Check command after \"Run:\""); }
                     //Convert single datastring to array of substrings deliniated by separator
                     String vars[] = dataStringOpt.split(separatorOpt);
                     varsT = dataStringOptT.split(separatorOpt, timestepsToOpt+1); //2nd entry is limit to prevent overflowing preallocated array
                     String varsP[] = dataStringOptP.split(separatorOpt);
                     String varsO[] = dataStringOptO.split(separatorOpt);
                     String varsS[] = dataStringOptS.split(separatorOpt);
                     //System.out.println("dsoHeatSet = " + dsoHeatSet); //for debugging
                     varsH = dsoHeatSet.split(separatorOpt, timestepsToOpt+1);
                     varsC = dsoCoolSet.split(separatorOpt, timestepsToOpt+1);
                     

                     // Writing data to file _____________________________________________
                     try{
                         // Create new file in Deployment folder with name including description, time and date string - Brian
                         // DataCVXOPT_mode_heatOrCool_YYYY-MM-DD_HH-mm.txt
                         File cvxFile = new File("DataCVXOPT_"+mode+"_"+heatOrCool+"_"+datime+".txt");

                         // If file doesn't exists, then create it
                         if (!cvxFile.exists()) {
                             cvxFile.createNewFile();
                             // Add headers and save them - Brian
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
                        System.out.println("Text Alert: Could not write DataCVXOPT.txt file.");
                         System.out.println(e);
                     } // End DataCVXOPT file ------------------------------------------------
        
                    // resetting 
                    var2save = 'Z';

                    // Initialize counter for setting setpoint temp for next hour 
                    p=0;
                    System.out.println("timestep p = "+String.valueOf(p));
                } //End hourly optimization
                // Outer loop makes this bit run every timestep that is not on a whole hour

                try{
                    if(heatOrCool.equals("heat")){
                        heatTemps[0]=Double.parseDouble(varsT[p+1]);
                        System.out.println("heatTemp: "+String.valueOf(heatTemps[0]));
                        coolTemps[0]=32.0; //Setback to prevent AC activation
                    }
                    else{ // Cooling
                        coolTemps[0]=Double.parseDouble(varsT[p+1]);
                        System.out.println("coolTemp: "+String.valueOf(coolTemps[0]));
                        heatTemps[0]=12.0; //Setback to prevent heater activation
                    }
                }
                catch(ArrayIndexOutOfBoundsException aie){ //Detect if couldn't get datastrings from Python. Usually indicates Python crashed.
                    System.out.println(aie);
                    System.out.println("Hiss... Python may have crashed and returned null. Check command after \"Run:\" ");
                }
                p=p+1;
                System.out.println("timestep p = "+String.valueOf(p));
                
               //-------------------------------------------------------------------------------------------------
               // Now figure out all stuff that needs to be sent to socket...
            
               // determine heating and cooling setpoints for each simID
               // will eventually change this part for transactive energy

             // Fuzzy control for Occupancy & Optimization _______________________________________________________
             // Brian rebuilt this so fuzzy doesn't replace the expanded occupancy comfort bounds with defaults
             double max_cool_temp, min_heat_temp; 
             double OFFSET = 0.6; // need to change slightly higher/lower so E+ doesnt have issues
             for(int i=0;i<numSockets;i++){ // Loop so that it would hypothetically work if we ever add more EP sims at once
                 // Determine minimum and maximum temperatures allowed from optimization or occupancy output as 'heating setpoint bounds' and 'cooling setpoint bounds' - Brian
                 min_heat_temp = Double.parseDouble(varsH[p]); // [p] because p incremented since previous usage
                 max_cool_temp = Double.parseDouble(varsC[p]);

                 // Fuzzy cool and heat are global variables that toggle only when criteria is met.
                 if (heatOrCool.equals("cool")){ //Cooling
                     if (zoneTemps[i] >= max_cool_temp){ // first check if going to exit maximum band
                         fuzzy_cool = -1;
                     } else if (zoneTemps[i] <= coolTemps[i]-1.0){ //colder than necessary, so allow to warm up
                         fuzzy_cool = 1;
                     }
                     coolTemps[i] = coolTemps[i] - 0.6 +fuzzy_cool*OFFSET;   // -0.6 so that oscillates 0-1.2 degree under cooling setpoint
                     heatTemps[i] = 12.0; // IF COOLING for now to avoid turning on heat
                }
                else{ // Heating
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
		         coolTemps[0]= 23.0;
		         heatTemps[0] = 15.0;
		    }
	    }
	//END FIXED SETPT -------------------------------------------------------------------


		//FUZZY CONTROL FOR NO OPTIMIZATION ______________________________________________________
		// Does not activate IF USING OPTIMIZATION
	    for(int i=0;i<numSockets;i++){
		  double OFFSET = 0.6; // need to change slightly higher/lower so E+ doesnt have issues

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
    
    // Display heating and cooling temp that gets sent, regardless of operating mode
    System.out.println("heatTemps[0] = "+heatTemps[0] );
    System.out.println("coolTemps[0] = "+coolTemps[0] );

          // Send values to each socket federate
          System.out.println("send to sockets interactions loop");
          for(int i=0;i<numSockets;i++){
            // simID = i;  I am leaving this here to remind myself that i is simID for each socket
            
            dataStrings[i] = "epGetStartCooling"+varNameSeparator;
            dataStrings[i] = dataStrings[i] + String.valueOf(coolTemps[i]) + doubleSeparator;
            
            dataStrings[i] = dataStrings[i] + "epGetStartHeating"+varNameSeparator;
            dataStrings[i] = dataStrings[i] + String.valueOf(heatTemps[i]) + doubleSeparator;
            
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
                    bw.write("CurrentTime\tHour\tzoneTemps[°C]\toutTemps[°C]\tsolarRadiation[W/m^2]\treceivedHeatTemp[°C]\treceivedCoolTemp[°C]\theatTemps[°C]\tcoolTemps[°C]\n");
                }

                FileWriter fw = new FileWriter(file.getAbsoluteFile(),true);
                BufferedWriter bw = new BufferedWriter(fw);

                // Write in file
                bw.write(currentTime+"\t"+hour+"\t"+ zoneTemps[0]+"\t"+ outTemps[0]+"\t"+ solarRadiation[0]+"\t" + receivedHeatTemp[0]+"\t"+ receivedCoolTemp[0]+"\t"+heatTemps[0]+"\t"+coolTemps[0]+"\n");
               
                // Close connection & save file
                bw.close();
            }
            catch(Exception e){
                System.out.println(e);
                System.out.println("Text Alert: Error when writing DataEP.txt file.");
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

        String vars[] = holder[simID].split(doubleSeparator);
        System.out.println("vars[0] = "+vars[0]);
        System.out.println("length of vars = " + vars.length);
        int j=0;
        for( String token : vars){
          System.out.println("token = " +token);
          String token1[] = token.split(varNameSeparator);
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
