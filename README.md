# TwoFedEPOpt
Optimization in Python connected to controller UCEF federate.   
Socket UCEF federate connects to EnergyPlus simulation. Compatible with simulations in the EP_SF_Optimization_House_Model folder or at https://github.com/SCU-Smart-Grid-CPS/Energy-Plus-Co-Sim-Models

### Run Instructions - UCEF
* Check weather and pricing spreadsheet dates in Controller.java and energyOptTset.py
* Change IP in config.txt
* Change control type, optimization status, run period, date_range, heatorcool, MODE, HRO settings in config.txt
* Compile Controller.java and Socket.java
* [UCEF Operation Instructions](https://github.com/SCU-Smart-Grid-CPS/smart-grid-energy-simulation-research/wiki)

### Run Instructions - EP
* Open .idf in EP
* Use idf editor to check the run period
* In Joe_ep_fmu folder, change ipconfig.txt to have your IP
* Zip the contents as "Joe_ep_fmu.fmu"
* Replace the Joe_ep_fmu.fmu in the folder with the EP .idf with the new fmu created in the previous step
* Wait until the Socket says "Waiting for Energy Plus simulations to join" before starting the simulation
* [FMU Instructions](https://github.com/SCU-Smart-Grid-CPS/smart-grid-energy-simulation-research/wiki/Link-EnergyPlus-and-UCEF-using-Joe_ep_fmu)

### Changelog

2022-12-14 (Brian)
* Uploading remaining files for V5.60 Stable.
* This is the final planned update to this project. Our research direction is moving away from optimization and toward more implementable control and modeling equity between different income levels. Future UCEF federation work will be in the [Supercontroller project.](https://github.com/SCU-Smart-Grid-CPS/UCEF-Supercontroller)

2022-10-01 (Brian)
* Version 5.60 Stable
* Integration with Appliance Scheduler (dishwasher)
* Optimizer improvements
* All settings are determined in Config.txt. No need to modify the Python or Java code files
* Full reliability validation; used in paper "Economic Benefits of a Novel HVAC Control Strategy in Grid-Interactive Residential Buildings"

2021-08-11 (Brian)
* Version 5.11 BETA adds GetWholesaleCAISO and getWeatherSolar support to version 5.0
* Improved runtime with GetWholesaleCAISO and getWeatherSolar files. 
* See documentation: https://github.com/SCU-Smart-Grid-CPS/TwoFedEPOpt/blob/brianupdates/EnergyPlusOpt2Fed/EnergyPlusOptOcc2Fed%20v5.1-%20What's%20New%20%26%20User%20Guide.pdf

2021-07-27 (Brian)
* Version 5.01 Stable release!
* Adds full occupancy support and many runtime improvements including no-recompile configuration, faster operation, and better debugging outputs
* See documentation: https://github.com/SCU-Smart-Grid-CPS/TwoFedEPOpt/blob/brianupdates/EnergyPlusOpt2Fed/EnergyPlusOptOcc2Fed%20v5.0-%20What's%20New%20%26%20User%20Guide.pdf

2021-07-14 (Brian)
* Complete overhaul of energyOptTset2hr.py
* Occupancy optimization with 3 modes, along with optimization of fixed and adaptive setpoints, with easy switching at the top of the file between the options using MODE
* Easy to change dates
* Easy to change cooling vs heating mode
* Extra debugging options
* Bug: indoor temperature prediction is one step behind the energy, causing it to miss some energy savings.
* A minor update to fix bug is expected by 2021-07-16

2021-06-30 (Brian)
* Automate file naming in Controller.java; instead of searching directories and appending the Data Summary text files with more data, it now goes directly to the current deployment directory and creates a new file with the current date and time as part of the file name to differentiate them. 
* => No need to change the file path when moving to a new computer! 
* => No need to rename or move the previous DataSummaryfromCVXOPT.txt and DataSummaryfromEP.txt, instead the files will be DataCVXOPT_YYYY-MM-DD_HH-mm.txt and DataEP_YYYY-MM-DD_HH-mm.txt
* Better comments in energyOptTset.py to explain optimization


2021-06-24 (Brian)
* fix syntax errors in energyOptTset.py so it runs correctly
* add easy toggle between adaptive/fixed setpoint and with/without optimization in energyOptTset.py and Controller.java. Instead of commenting out large chunks of code and finding the right lines, instead just change two booleans in each code and it switches nicely.


2021-06-23 (Brian)
* added easier switching between adaptive and fixed setpoint methods in energyOptTset.py
* verified to work on cluster
* removed obsolete files to avoid confusion
* various updates since previous uploaded version
* commenting and readability significantly improved

