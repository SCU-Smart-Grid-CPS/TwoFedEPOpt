/*
File:           controller.java
Project:        EnergyPlusOptOcc2Fed
Author(s):      PJ McCurdy, Kaleb Pattawi, Brian Woo-Shem, Hannah Covington
Version:        5.41
Last Updated:   2022-05-23 by Brian
Notes: Code for the optimization simulations. Should compile and run but may not have perfect results.
Run:   Change file paths in this code. Then build or build-all. Run as part of federation.

*Changelog:
    * Merged Brian's v5.3 with Hannah's Appliance Scheduler v2.2
    * Added location variable
    * Requires Occupancy8Days.csv
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
import java.util.*;
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

    // Defining Global Variables ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    // Basic IO
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
    
    // for no time delay
    boolean receivedSocket = false;
    boolean receivedMarket = false;
    boolean receivedReader = false;
    int waitTime = 0;
    String timestep_Socket = "";
    //String timestep_Reader = "";  //UNcomment if using Reader and Market federates
    //String timestep_Market = "";
    //String timestep_Controller = "";
    
    // Initializing for Fuzzy Control
    int fuzzy_heat = 0;  // NEEDS TO BE GLOBAL VAR outside of while loop
    int fuzzy_cool = 0;  // NEEDS TO BE GLOBAL VAR outside of while loop
    
    // For Setpoints & Optimization ========================================================
    //Determine Setpoint Type --- Leave these false for config.txt input
    boolean optimizeSet = false; //True if optimized, false if not optimized
    boolean adaptiveSet = false; //True if using adaptive setpoint, false if fixed setpoint. Not used if optimizeSet = true.
    boolean occupancySet = false; //Does it use occupancy?
    // Number of optimization to run per hour. Default is 1
    int timestepsToOpt = 12;
    int nopt = 12/timestepsToOpt;
    // Temperature and setpoint data
    String[] varsT = new String[timestepsToOpt+2]; 
    String[] varsH = new String[timestepsToOpt+2]; 
    String[] varsC = new String[timestepsToOpt+2];
    // Keystrings for encoding and decoding data into a string
    String varNameSeparator = "@";
    String doubleSeparator = ",";
    String optDataString = "";
    int day = 0, p = 0;
    //==========================================================================
    
    //For appliance scheduling =================================================
    //input variables
    int timestep = 12; //timesteps PER hour
	int state;
	int runTime = 12; //number of time steps the appliance is activated
	int sleepTime = 22*timestep; //time that the house is asleep
	int wakeTime = 6*timestep; //time that the house is awake
	int numActPerDay = 1; //number of activations 
	int numActToday = 0;
	int dayCount = 1;
	double dailyActivationProb = .59;
	double activationProb = 0;
	int numOccupiedToday = 0;
	ArrayList<Integer> occupancyData = new ArrayList<Integer>();
	ArrayList<Integer> activationHistory = new ArrayList<Integer>(); // can change to just a variable, not ann array list
	ArrayList<Integer> stateHistory = new ArrayList<Integer>();
	ArrayList<Double> randomNumHistory = new ArrayList<Double>();
	ArrayList<Integer> timeStepsOccupied = new ArrayList<Integer>();
   //============================================================================
    
    //Get date + time string for output file naming.
    // Need to do this here because otherwise the date time string might change during the simulation
    String datime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"));

    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

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
        
        // Read simulation settings from config.txt =============================================
        log.info("Getting Configuration Settings: ");
        File cf = new File("config.txt");
        BufferedReader br = new BufferedReader(new FileReader(cf));
        String st = "";
        String mode = "";
        String heatOrCool = "";
        char hcc = 'z';
        String dateRange = "";
        String loc = "";
        //char wholesaleType = 'z';
        String priceType = "";
        String pythonCommand = "python3";
        boolean writeFile = false;
        String optimizerFile = "energyOptTset2hr.py";
        String setpointFile = "occupancyAdaptSetpoints.py";

        while ((st = br.readLine())!=null){
            log.info(st);
            if(st.contains("MODE:")){ //Use contains so tagline can have other instructions
                mode = br.readLine();
            }
            else if(st.contains("heatorcool:")){
                heatOrCool = br.readLine(); // Immutable
                hcc = heatOrCool.charAt(0); // should be one of: h, c, a. MAY change during auto setting
            }
            else if(st.contains("date_range:")){
                dateRange = br.readLine();
            }
            else if(st.contains("optimize:")){
                optimizeSet = Boolean.parseBoolean(br.readLine());
            }
            else if(st.contains("location:")){
                loc = br.readLine();
            }
            else if(st.contains("wholesale_type:")){
                //wholesaleType = br.readLine().charAt(0);
                priceType = br.readLine();
            }
            else if(st.contains("python_command:")){
                pythonCommand = br.readLine();
            }
            else if(st.contains("write_extra_data_files:")){
                writeFile = Boolean.parseBoolean(br.readLine());
            }
            else if(st.contains("optimizer_code_file_name:")){
				optimizerFile = br.readLine();
			}
			else if(st.contains("occupancy_adaptive_setpoints_code_file_name:")){
				setpointFile = br.readLine();
			}
        }
        log.info("Mode: " + mode);
        log.info("Heat or Cool: " + heatOrCool);
        log.info("Date Range: " + dateRange);
        log.info("Optimize: " + optimizeSet);
        log.info("Location: " + loc);
        log.info("Energy Pricing Type: " + priceType);
        // if not optimizing, figure out occupancySet and adaptiveSet booleans. Note optimize uses only MODE, not occupancySet or adaptiveSet.
        if(!optimizeSet){
            if(mode.contains("occupancy")){ occupancySet = true;}
            else if(mode.contains("adaptive")){ adaptiveSet = true;}
            else if(mode.equals("")){ System.out.println("Text Alert: config.txt missing or contains invalid parameters."); }
        }
        // end config.txt ================================================================
        
        // Reading Occupancy Information ==================================================
        File data = new File("Occupancy8Days.csv");
	    Scanner scanner = new Scanner(data);
	    scanner.useDelimiter(",");
	    while (scanner.hasNext()) {
		    occupancyData.add(scanner.nextInt());
	    }
	    scanner.close();
	    System.out.println("OCCUPANCY:");
	    System.out.println(occupancyData);
	
	    //getting amount of occupancy for each day
	    for (int k = 0; k<occupancyData.size(); k++) {
		    if (occupancyData.get(k) == 1) {
			    numOccupiedToday = numOccupiedToday + 1;
		    }
		    if ((k+1)%(24*timestep) == 0 && k!=0) {
			    timeStepsOccupied.add(numOccupiedToday); 
			    numOccupiedToday = 0;
		    }
	    }
	    System.out.println("TIME STEPS OCCUPIED PER DAY:");
	    System.out.println(timeStepsOccupied);
	
        //end of occupancy information =================================================
        
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
            //log.info("timestep before receiving Socket/Reader: ",currentTime);
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
                    if(waitTime > 200){
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

        // SETPOINTS & OPTIMIZATION ===============================================================
        // Reset variables to defaults
        double hour = (double) ((currentTime%288) / 12);
        System.out.println("hour is: "+hour);
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
        String autopycmd = "";
        
        // At beginning of the day, increment day
        if (hour == 0){
             day = day+1;
         }
        
        // WARNING: WORK IN PROGRESS! PYTHON PART DOES NOT WORK YET SO DON'T USE THIS MODE!
        // Automatic Heat vs Cool Selection ----------------------------------------
        // If automatic heating and cooling mode, and it is the beginning of an hour
        if (heatOrCool.equals("auto") && hour%nopt == 0){
            // Run autoHeatCool.py
            Process autorun;
            autopycmd = "python3 ./autoHeatCool.py " + sday + " " + sblock + " " + dateRange;
            try{
                autorun = Runtime.getRuntime().exec(autopycmd); 
                System.out.println("Run:  " + autopycmd); //Display command run for user debugging
                BufferedReader aInput = new BufferedReader(new InputStreamReader(autorun.getInputStream()));

                // Gets input data from Python. Should be single string with either "heat" or "cool"
                // AS long as there is another output line with data,
                while ((s = aInput.readLine()) != null) {
                    switch (s) {
                        case "heat": hcc = 'h'; System.out.println("Autoselect Set to HEAT"); break;
                        case "cool": hcc = 'c'; System.out.println("Autoselect Set to COOL"); break;
                        case "Traceback (most recent call last):":
                            System.out.println("\nHiss... Python autoHeatCool.py crash detected. Try pasting command after \"Run\" in the terminal and debug Python.");
                            System.out.println("Alert! Using heat/cool setting from previous hour, which is: " + hcc);
                            break;
                        default:
                            System.out.println("Warning: Unexpected String from autoHeatCool.py: \n    " + s);
                    } //End switch
                    s = null;
                } // End While
            } // End try
            catch (IOException e) {
                e.printStackTrace();
                System.out.println("\nHiss... Python crashed or failed to run. Try pasting command after \"Run\" in the terminal and debug Python."); 
            } // End catch
        } //End run autoHeatCool -------------------------------------------------------

        // Run Python Setpoints Code ---------------------------------------------------
        // possibly get rid of this if statement and get rid of computing setpoints in controller java at all 
        // because I don't trust it. & would make looping in the supercontroller easier. However this would be slower
        // put adaptive here too because of problems with fuzzy with other method.
        if (optimizeSet || occupancySet || adaptiveSet){
            // On the whole hours only, run the optimization ---------------------------
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
                         pycmd = "python3 ./energyOptTset2hr.py " + sday +" " +sblock +" "+ String.valueOf(zoneTemps[0])+ " " + String.valueOf(24) + " " + timestepsToOpt + " " + hcc + " " + mode + " " + dateRange + " " + loc + " " + priceType; 
                    }
                    else{ // Call Python adaptive and occupancy setpoints code with necessary info
                        pycmd = "python3 ./occupancyAdaptSetpoints.py " +sday +" " +sblock +" "+ String.valueOf(zoneTemps[0])+ " " + String.valueOf(24) + " " + timestepsToOpt + " " + hcc + " " + mode + " " + dateRange + " " + loc + " " + priceType;
                    }
                    System.out.println("Run:  " + pycmd); //Display command used for debugging
                    pro = Runtime.getRuntime().exec(pycmd); // Runs command

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
                                var2save = 'Z';
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
                    
                    // End getting data from Python Setpoint Code ------------------------
                    
                    // Writing data to file _____________________________________________
                    if (writeFile){
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
                            for (int in =0;in<13;in++) {
                                bw.write(vars[in]+"\t"+varsT[in]+"\t"+varsP[in]+"\t"+varsO[in]+"\t"+varsS[in]+"\t"+varsH[in]+"\t"+varsC[in]+"\n");
                            }
                            // Close and save file
                            bw.close();
                        }
                        catch(Exception e){
                            System.out.println("Text Alert: Could not write DataCVXOPT.txt file.");
                            System.out.println(e);
                        } 
                    }// End DataCVXOPT file ------------------------------------------------
        
                    // resetting 
                    var2save = 'Z';

                    // Initialize counter for setting setpoint temp for next hour 
                    p=0;
                    System.out.println("timestep p = "+String.valueOf(p));
                } //End hourly optimization ------------------------------------------------
                
                // Below here runs every timestep that is not on a whole hour -------

                try{
                    if(hcc == 'h'){
                        heatTemps[0]=Double.parseDouble(varsT[p+1]);
                        System.out.println("heatTemp: "+String.valueOf(heatTemps[0]));
                        coolTemps[0]=32.0; //Setback to prevent AC activation
                    }
                    else{ // Cooling
                        coolTemps[0]=Double.parseDouble(varsT[p+1]);
                        System.out.println("coolTemp: "+String.valueOf(coolTemps[0]));
                        heatTemps[0]=15.0; //Setback to prevent heater activation
                    }
                }
                catch(ArrayIndexOutOfBoundsException aie){ //Detect if couldn't get datastrings from Python. Usually indicates Python crashed.
                    System.out.println(aie);
                    System.out.println("Hiss... Python may have crashed and returned null. Check command after \"Run:\" ");
                }
                p=p+1;
                System.out.println("timestep p = "+String.valueOf(p));
                
            //-------------------------------------------------------------------------------------------------            
            // determine heating and cooling setpoints for each simID
            // will eventually change this part for transactive energy

            // Fuzzy control for Occupancy & Optimization _______________________________________________________
            // Brian rebuilt this so fuzzy doesn't replace the expanded occupancy comfort bounds with defaults
            //double max_cool_temp, min_heat_temp; 
            double OFFSET = 0.5; // need to change slightly higher/lower so E+ doesnt have issues
            for(int i=0;i<numSockets;i++){ // Loop so that it would hypothetically work if we ever add more EP sims at once
                // Determine minimum and maximum temperatures allowed from optimization or occupancy output as 'heating setpoint bounds' and 'cooling setpoint bounds' - Brian
                //min_heat_temp = Double.parseDouble(varsH[p]); // [p] because p incremented since previous usage
                //max_cool_temp = Double.parseDouble(varsC[p]);
                // Alternately it might actually supposed to be coolTemps and heatTemps?
                // In which case would be identical to the non-optimized adaptive and fixed cases.

                // Fuzzy cool and heat are global variables that toggle only when criteria is met.
                if (hcc == 'c'){ //Cooling
                    if (zoneTemps[i] >= coolTemps[i] - 0.2){ // if likely to exceed maximum band
                        fuzzy_cool = -1;
                    } else if (zoneTemps[i] <= coolTemps[i]-1.1){ // if colder than necessary, allow to warm up
                        fuzzy_cool = 1;
                    }
                    coolTemps[i] = coolTemps[i] - 0.6 + fuzzy_cool * OFFSET;   // -0.6 so that oscillates 0.1-1.1 degree under cooling setpoint
                    heatTemps[i] = 15.0; // IF COOLING for now to avoid turning on heat
                }
                else{ // Heating
                    if (zoneTemps[i] <= heatTemps[i] + 0.2){ // if likely to exceed minimum band
                        fuzzy_heat = 1;
                    } else if (zoneTemps[i] >= heatTemps[i]+1.1){
                        fuzzy_heat = -1;
                    }
                    heatTemps[i] = heatTemps[i] + 0.6 + fuzzy_heat * OFFSET;  // +0.6 so that oscillates 0.1-1.1 degree above heating setpoint
                    coolTemps[i] = 32.0; // IF HEATING for now to avoid turning on AC
                }
            } // End fuzzy
        //END OPTIMIZATION or OCCUPANCY --------------------------------------------------------------
        } // end giant if to determine if Optimized or occupancy

        else{ // Not optimized and not occupancy

        // Adaptive Setpoint Control: _____________________________________________________________
            // Not really needed, could be replaced by occupancyAdaptSetpoints.py
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

                if(hcc == 'h'){
                    coolTemps[0]= 30.2;     // 23.0
                }
                else{
                    heatTemps[0] = 15.0;   // 20.0 
                }
            } 
        // End Adaptive Setpoint Control -------------------------------------------------------

        // FIXED SETPOINT _________________________________________________________________________
            else{ //Not adaptive, so fixed
                if(hcc == 'h'){
                    coolTemps[0] = 32.0;
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
            double OFFSET = 0.5; // need to change slightly higher/lower so E+ doesnt have issues
            for(int i=0;i<numSockets;i++){
                if (hcc == 'c'){
                    // For Cooling 1 degree under Cooling setpoint:
                    if (zoneTemps[i] >= coolTemps[i] - 0.2){ // check if likely to exceed maximum band. Was -0.1 but want to be more aggressive
                        fuzzy_cool = -1;
                    } else if (zoneTemps[i] <= coolTemps[i]-1.1){
                        fuzzy_cool = 1;
                    }
                    coolTemps[i] = coolTemps[i] - 0.6 +fuzzy_cool*OFFSET;   // - 0.6 so that oscillates 0.1-1.1 degree under cooling setpoint
                    heatTemps[i] = 15.0; // Override for now to avoid turning on AC. 
                    }
                else{ //Heat
                    // For Heating 1 degree under Heating setpoint:
                    if (zoneTemps[i] <= heatTemps[i] + 0.2){ // check if likely to exceed minimum band
                        fuzzy_heat = 1;
                    } else if (zoneTemps[i] >= heatTemps[i]+1.1){
                        fuzzy_heat = -1;
                    }
                    heatTemps[i] = heatTemps[i] + 0.6 +fuzzy_heat*OFFSET;  // + 0.6 so that oscillates 0.1-1.1 degree above heating setpoint
                    coolTemps[i] = 32.0; // Override for now to avoid turning on heat. 
                }
            }
            // END FUZZY NO OPT ------------------------------------------------------------
    } // end of giant else to indicate not optimized
    
    // End Setpoints =============================================================================
    
    //BEGIN APPLIANCE SCHEDULER ============================================================
    
    //System.out.println("TIMESTEP FOR APPLIANCE: " + (int)currentTime);
    //System.out.println("OCCUPANCY AT TIMESTEP: " + occupancyData.get((int)currentTime));
    
    //clearing information from past day and getting new probability of activation
    //clearing past activations and finding new activation prob if it is a new day
    if (currentTime == 0) {
	System.out.println("DAY COUNTER: " + dayCount);
	activationProb = dailyActivationProb/timeStepsOccupied.get(dayCount-1);
	System.out.println(activationProb);
    }else if (currentTime+1 == occupancyData.size()){
	System.out.println("END OF SIMULATION");
    }else if ((currentTime+1)%(24*timestep) == 0) {
	numActToday = 0;
	dayCount = dayCount + 1;
	sleepTime = sleepTime + timestep*24;
	wakeTime = wakeTime + timestep*24;
	System.out.println("DAY COUNTER: "+ dayCount);
	activationProb = dailyActivationProb/timeStepsOccupied.get(dayCount-1);
	//System.out.println(activationProb);
    }	
    //make sure activation history takes precedence
    if (activationHistory.size() > 0 && activationHistory.size() < runTime){
	state = 1;
	activationHistory.add(state);
	stateHistory.add(state);
	randomNumHistory.add(0.0);
    }else {
	    //dealing with occupancy
	if (occupancyData.get((int)currentTime) == 1) {
	    //dealing with wake/sleep time
	    if (currentTime > wakeTime && currentTime < sleepTime) {
		//dealing with number of activations per day
		if (numActToday < numActPerDay) {
		    //dealing with length of operation
		    if (activationHistory.size() == runTime) {
			state = 0;
			stateHistory.add(state);
			numActToday = numActToday + 1;
			activationHistory.clear();
			randomNumHistory.add(0.0);
		    }else if (activationHistory.size() == 0) {
			double randomNum = Math.random(); //random num for monte carlo or add whatever determiner I decide
			randomNumHistory.add(randomNum);
			if (randomNum < activationProb) {
			    state = 1;
			    activationHistory.add(state);
			    stateHistory.add(state);
			}else {
			    state = 0;
			    stateHistory.add(state); // end determiners
			}
		    }
		}else {
		    state = 0;
		    stateHistory.add(state);
		    randomNumHistory.add(0.0);
		}
	    }else {
		state = 0;
		stateHistory.add(state);
		randomNumHistory.add(0.0);
	    }
	}else {
	    state = 0;
	    stateHistory.add(state);
	    randomNumHistory.add(0.0);
	}
    }
    //System.out.println("STATE HISTORY:");
    //System.out.println(stateHistory);
    //System.out.println("RANDOM NUMBERS:");
    //System.out.println(randomNumHistory);

    //END APPLIANCE SCHEDULER =================================================================
    
    
    // DISPLAY WHAT GETS SENT, regardless of operating mode --------------------------------------ADD VARIABLES IF NEEDED
    System.out.println("heatTemps[0] = "+heatTemps[0] );
    System.out.println("coolTemps[0] = "+coolTemps[0] );
    System.out.println("Dishwasher Activation[0]:"+ state);
    
    // SEND VALUES to each socket federate -------------------------------------------------------ADD VARIABLES IF NEEDED
    System.out.println("send to sockets interactions loop");
    for(int i=0;i<numSockets;i++){
        // simID = i;  I am leaving this here to remind myself that i is simID for each socket
            
        dataStrings[i] = "epGetStartCooling"+varNameSeparator;
        dataStrings[i] = dataStrings[i] + String.valueOf(coolTemps[i]) + doubleSeparator;
            
        dataStrings[i] = dataStrings[i] + "epGetStartHeating"+varNameSeparator;
        dataStrings[i] = dataStrings[i] + String.valueOf(heatTemps[i]) + doubleSeparator;

        dataStrings[i] = dataStrings[i] + "dishwasherSchedule"+varNameSeparator;
        dataStrings[i] = dataStrings[i] + String.valueOf(state) + doubleSeparator;

        //print out result
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
	// End Send to Socket -----------------------------------------------------------


            // Writing data to file ______________________________________________________
        if (writeFile){
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
                    bw.close();
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
        }// End File Write -------------------------------------------------------------

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
        //System.out.println("numVars[simID] = " + numVars[simID]);
        holder[simID] = interaction.get_dataString();
        //System.out.println("holder[simID] = "+ holder[simID] );

        //System.out.println("handle interaction loop");

        String vars[] = holder[simID].split(doubleSeparator);
        //System.out.println("vars[0] = "+vars[0]);
        System.out.println("length of vars = " + vars.length);
        int j=0;
        for( String token : vars){
            //System.out.println("token = " +token);
            String token1[] = token.split(varNameSeparator);
            //System.out.println("token1[0] = "+token1[0]);
            //System.out.println("token1[1] = "+token1[1]);
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
