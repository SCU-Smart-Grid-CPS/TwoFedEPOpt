# energyOptTset2hr.py
# Author(s):    PJ McCurdy, Kaleb Pattawi, Brian Woo-Shem
# Version:      5.30 BETA
# Last Updated: 2021-01-25
# Changelog:
# - Added debugging noHVAC
# - Added switching for adaptive vs fixed vs occupancy control - Brian
# - Added occupancy optimization - Brian
# - Fixed optimizer should not know occupancy status beyond current time
# - Merging 2 codes, keeping the best characteristics of each
# - Base code is energyOptTset2hr_kaleb.py, with stuff from the PJ/Brian one added
# - Runtime reduced from 2.0 +/- 0.5s to 1.0 +/- 0.5s by moving mode-specific steps inside "if" statements
#   and taking dataframe subsets before transformation to matrices
# - Both heat and cool modes can optimize without out of bounds errors.
# - Fixed off by one error where indoor temperature prediction is one step behind energy, causing it to miss some energy savings.
# Usage:
#   Typically run from Controller.java in UCEF energyPlusOpt2Fed or EP_MultipleSims. Will be run again for every hour of simulation.
#   For debugging, can run as python script. In folder where this is stored:
#   ~$ python energyOptTset2hr.py [Parameters - See "ACCEPT INPUT PARAMETERS" section] 
#   Requires OutdoorTemp.xlsx, occupancy_1hr.csv, Solar.xlsx, WholesalePrice.xlsx in run directory
#   Check constants & parameters denoted by ===> IMPORTANT <===

# Import Packages ---------------------------------------------------------------
from cvxopt import matrix, solvers
from cvxopt.modeling import op, dot, variable
import time
import pandas as pd
import numpy as np
import sys
from scipy.stats import norm
from configparser import ConfigParser

# IMPORTANT PARAMETERS TO CHANGE ------------------------------------------------

# ===> WHEN TO RUN <=== CHECK IT MATCHES EP!!!
# OR can instead designate in [PARAMETERS]
# Make sure to put in single quotes
date_range = '2020-08-01_2020-08-31' #'2020-6-29_2020-7-05' 

# Location
loc = "Default"

# Wholesale Type
# 'r' = real-time
# 'd' = day-ahead
wholesaleType = 'r'

# ===> SET HEATING VS COOLING! <===
# OR can instead designate in [PARAMETERS] -- changed so also accepts 'h' or 'c'
#   'heat': only heater, use in winter
#   'cool': only AC, use in summer
heatorcool = 'cool'

# ===> MODE <===
# OR can instead designate in [PARAMETERS]
#   'occupancy': the primary operation mode. Optimization combining probability data and current occupancy status
#   'occupancy_prob': optimization with only occupancy probability (NOT current status)
#   'occupancy_sensor': optimization with only occupancy sensor data for current occupancy status
#   'adaptive90': optimization with adaptive setpoints where 90% people are comfortable. No occupancy
#   'fixed': optimization with fixed setpoints. No occupany.
#   'occupancy_preschedule': Optimize if occupancy status for entire prediction period (2 hrs into future) is known, such as if people follow preset schedule.
MODE = 'occupancy'

# ===> LEGACY <===
# Run older fileget setting using pd on .xlsx files for Outdoor Temp, Solar, & Wholesale Price.
# Old method was excrutiatingly slow because it computes for entire year-size datasets when optimizer only needs 2 hrs of data,
# re-computed things every single run, and won't work with the GetWeatherSolar EP and getWholesaleCAISO input data collection methods
legacy = False

# ===> Human Readable Output (HRO) SETTING <===
# Extra outputs when testing manually in python or terminal
# These may not be recognized by UCEF Controller.java so HRO = False when running full simulations
HRO = True

# Debug Setting - For developers debugging the b, D, AA, ineq matrices, leave false if you have no idea what this means
HRO_DEBUG = True

# Print HRO Header
if HRO:
    import datetime as datetime
    print()
    print('=========== energyOptTset2hr.py V5.3 ===========')
    print('@ ' + datetime.datetime.today().strftime("%Y-%m-%d_%H:%M:%S") + '   Status: RUNNING')

# Constants that should not be changed without a good reason --------------------------------

# Temperature Data refresh rate [min]. Typical data with 5 minute intervals use 5. Hourly use 60.
temp_data_interval = 5

# Time constants. Default is set for 1 week.
n= 24 # number of timesteps within prediction windows (24 x 5-min timesteps in 2 hr window). Default - can be overwritten
nt = 12 # Number of effective predicted timesteps - Default
n_occ = 12 # number of timesteps for which occupancy data is considered known (12 = first hour for hourly occupancy data)
timestep = 5*60
# No longer used but kept for potential future use:
#days = 7
#totaltimesteps = days*12*24+3*12


# ACCEPT INPUT PARAMETERS ----------------------------------------------------------------------
# From UCEF or command line
# Run as:
#           python energyOptTset2hr.py day hour temp_indoor_initial
#           python energyOptTset2hr.py day hour temp_indoor_initial n nt
#           python energyOptTset2hr.py day hour temp_indoor_initial n nt heatorcool
#           python energyOptTset2hr.py day hour temp_indoor_initial n nt heatorcool MODE
#           python energyOptTset2hr.py day hour temp_indoor_initial n nt heatorcool MODE date_range
#           python energyOptTset2hr.py day hour temp_indoor_initial heatorcool
#           python energyOptTset2hr.py day hour temp_indoor_initial heatorcool MODE
#           python energyOptTset2hr.py day hour temp_indoor_initial heatorcool MODE date_range
#           python energyOptTset2hr.py day hour temp_indoor_initial heatorcool nt
#           python energyOptTset2hr.py day hour temp_indoor_initial n MODE
#           python energyOptTset2hr.py day hour temp_indoor_initial n MODE date_range
#   Note: Linux use 'python3' or 'python3.9' instead of 'python'
#         Windows use 'py'
# where:
#   day = [int] day number in simulation. 1 =< day =< [Number of days in simulation]
#   hour = [int] hour of the day. 12am = 0, 12pm = 11, 11pm = 23
#   temp_indoor_initial = [float] the initial indoor temperature in deg C
#   n = [int] number of 5 minute timesteps to use in optimization. Requirement: n >= nt
#   nt = [int] number of 5 minute timesteps to return prediction for. Requirement: n >= nt
#   heatorcool = [str] see ===> SET HEATING VS COOLING! <===
#   MODE = [str] see ===> MODE <===
#   date_range = [str] see ===> WHEN TO RUN <===
day = int(sys.argv[1])
block = int(sys.argv[2]) +1+(day-1)*24 # block goes 0:23 (represents the hour within a day)
temp_indoor_initial = float(sys.argv[3])

# Get extra inputs, if they exist
if 4 < len(sys.argv):
    try: n = int(sys.argv[4])
    except ValueError: heatorcool = sys.argv[4]
    if 5 < len(sys.argv):
        try: nt = int(sys.argv[5])
        except ValueError: MODE = sys.argv[5]
        if 6 < len(sys.argv):
            try: # if previous one was an integer, it was n, so next one is MODE
                nt = int(sys.argv[5])
                heatorcool = sys.argv[6]
            except ValueError: date_range = sys.argv[6]
            if 7 < len(sys.argv): # Now guaranteed to be strings, per syntax rules
                MODE = sys.argv[7]
                if 8 < len(sys.argv): 
                    date_range = sys.argv[8]
                    if 9 < len(sys.argv): 
                        loc = sys.argv[9]
                        if 10 < len(sys.argv): wholesaleType = sys.argv[10]

# constant coefficients for indoor temperature equation & pricing ------------------------------
# Read config file to get constants for this simulation. Need to pass location "loc" from Controller.java
cfp = ConfigParser()
cfp.read('optCoeff.ini')
sectionName = loc + "_" + heatorcool[0] + "_3" #Generate formulaic temp coeff section name
try: #For dealing with various errors if the sectionName is not found in .ini file
    try: 
        c1 = float(cfp.get(sectionName,'c1'))
        c2 = float(cfp.get(sectionName,'c2'))
        c3 = float(cfp.get(sectionName,'c3'))
        PRICING_MULTIPLIER = float(cfp.get('Pricing_Constants', 'PRICING_MULTIPLIER'))
        PRICING_OFFSET = float(cfp.get('Pricing_Constants', 'PRICING_OFFSET'))
    except (configparser.NoSectionError): c1,c2,c3 = [1,2,3]
except (NameError, ValueError): # Old defaults
    c1 = 1.72*10**-5 #1.72*10**-5 #2.66*10**-5
    c2 = 7.20*10**-3 #0.0031
    c3 = 1.55*10**-7 #3.10*10**-7 #3.58*10**-7
    PRICING_MULTIPLIER = 15.0 #4.0 Changed to try to make optimization more effective (from Dr. Lee suggestion)
    PRICING_OFFSET = 0.005 #0.10

if HRO_DEBUG:
    print('\nGot constants: c1 = ' + str(c1) + '   c2 = ' + str(c2) + '   c3 = ' + str(c3))
    print('\t Pricing Multiplier = ' + str(PRICING_MULTIPLIER))
    print('\t Pricing Offset = ' + str(PRICING_OFFSET) + '\n')

# Get data from excel/csv files ------------------------------------------------------
startdat = (block-1)*12
#   All xlsx files have single col of numbers at 5 minute intervals, starting on 2nd row. Only 2nd row and below is detected.
if legacy: # These are excrutiatingly slow and aren't compatible with the new data collection methods
    # Get outdoor temps [deg C]
    enddat = (block-1)*12+n
    if temp_data_interval == 5: # 5 minutes can use directly
        outdoor_temp_df = pd.read_excel('OutdoorTemp.xlsx', sheet_name=date_range,header=0, skiprows=startdat, nrows=n)
        outdoor_temp_df.columns = ['column1']
        temp_outdoor = matrix(outdoor_temp_df.iloc[0:n,0].to_numpy())
    elif temp_data_interval == 60: #  for hourly data
        outdoor_temp_df = pd.read_excel('OutdoorTemp.xlsx', sheet_name=date_range+'_2021_1hr',header=0)
        start_date = datetime.datetime(2021,2,12)
        dates = np.array([start_date + datetime.timedelta(hours=i) for i in range(8*24+1)])
        outdoor_temp_df = outdoor_temp_df.set_index(dates)
        # Changed to do linear interpolation of temperature to avoid the sudden jumps
        outdoor_temp_df = outdoor_temp_df.resample('5min').Resampler.interpolate(method='linear')
        temp_outdoor_all=matrix(outdoor_temp_df.to_numpy())
        outdoor_temp_df.columns = ['column1']
        temp_outdoor = matrix(outdoor_temp_df.iloc[(block-1)*12:(block-1)*12+n,0].to_numpy())
        #Leave outdoor_temp_df as type dataframe for later computations

    # get solar radiation. Solar.xlsx must be in run directory
    sol_df = pd.read_excel('Solar.xlsx', sheet_name=date_range, nrows=(block-1)*12+n+1)
    q_solar = matrix(sol_df.iloc[(block-1)*12:(block-1)*12+n,0].to_numpy())
    
    # get wholesale prices. WholesalePrice.xlsx must be in run directory
    price_df = pd.read_excel('WholesalePrice.xlsx', sheet_name=date_range, nrows=(block-1)*12+n+1) 
    cc=matrix(price_df.iloc[(block-1)*12:(block-1)*12+n,0].to_numpy())*PRICING_MULTIPLIER/1000+PRICING_OFFSET
else:
    # Use GetWeatherSolar output
    # Filename format is  WeatherSolar_SF_2020-01-01_2020-01-07.csv
    wfile = "WeatherSolar_" + loc + "_" + date_range + ".csv"
    if loc == "default": wfile="GetWeatherSolar.csv" # Catch for older versions
    # Contains [date/time, outdoor temp, humidity, solar radiation]. Need 1 and 3.

    outtempnp = np.genfromtxt(wfile, skip_header=startdat+1, max_rows=n, delimiter=',', usecols=1)
    temp_outdoor = matrix(outtempnp)

    solarrad= np.genfromtxt(wfile, skip_header=startdat+1, max_rows=n, delimiter=',', usecols=3)
    q_solar = matrix(solarrad)
    
    #Get wholesale data matching date range and type
    if 'd' in wholesaleType.lower(): pfile = "WholesaleDayAhead_" + date_range + ".csv"
    else: pfile = "WholesaleRealTime_" + date_range + ".csv"
    wholesale= np.genfromtxt(pfile, skip_header=startdat, max_rows=n, delimiter=',')
    cc = matrix(wholesale)*PRICING_MULTIPLIER/1000+PRICING_OFFSET



# Compute Adaptive Setpoints ---------------------------------------------------------------
# OK to remove if MODE != 'fixed' on your personal version only if the fixed mode is never used. Keep in master
if MODE != 'fixed':
    # Max and min for heating and cooling in adaptive setpoint control for 90% of people [deg C]
    HEAT_TEMP_MAX_90 = 26.2
    HEAT_TEMP_MIN_90 = 18.9
    COOL_TEMP_MAX_90 = 30.2
    COOL_TEMP_MIN_90 = 22.9
    if legacy:
        # use outdoor temps to get adaptive setpoints using lambda functions
        outdoor_to_cool90 = lambda x: x*0.31 + 19.8
        outdoor_to_heat90 = lambda x: x*0.31 + 15.8
        adaptive_cooling_90 = outdoor_temp_df.apply(outdoor_to_cool90)
        adaptive_heating_90 = outdoor_temp_df.apply(outdoor_to_heat90)
        # When temps too low or too high set to min or max (See adaptive setpoints)
        adaptive_cooling_90.loc[(adaptive_cooling_90['column1'] < COOL_TEMP_MIN_90)] = COOL_TEMP_MIN_90
        adaptive_cooling_90.loc[(adaptive_cooling_90['column1'] > COOL_TEMP_MAX_90)] = COOL_TEMP_MAX_90
        adaptive_heating_90.loc[(adaptive_heating_90['column1'] < HEAT_TEMP_MIN_90)] = HEAT_TEMP_MIN_90
        adaptive_heating_90.loc[(adaptive_heating_90['column1'] > HEAT_TEMP_MAX_90)] = HEAT_TEMP_MAX_90
        # change from pd dataframe to matrix
        adaptiveCool = matrix(adaptive_cooling_90.to_numpy())
        adaptiveHeat = matrix(adaptive_heating_90.to_numpy())
    else: #New method avoiding pandas dataframes because they are slow and annoying
        # Everything is already in np arrays
        adc90 = np.zeros(n)
        adh90 = np.zeros(n)
        for i in range(0,len(outtempnp)):
            # Adaptive cooling setpoint
            adc90[i]=outtempnp[i]*0.31 + 19.8
            if adc90[i] > COOL_TEMP_MAX_90: adc90[i] = COOL_TEMP_MAX_90
            elif adc90[i] < COOL_TEMP_MIN_90: adc90[i] = COOL_TEMP_MIN_90
            adaptiveCool = matrix(adc90)
            
            # Adaptive heating setpoint
            adh90[i]= outtempnp[i]*0.31 + 15.8
            if adh90[i] > HEAT_TEMP_MAX_90: adh90[i] = HEAT_TEMP_MAX_90
            elif adh90[i] < HEAT_TEMP_MIN_90: adh90[i] = HEAT_TEMP_MIN_90
            adaptiveHeat = matrix(adh90)
        #if HRO_DEBUG:
            #print(adaptiveCool)
            #print(adaptiveHeat)

# Get Occupancy Data & Compute Setpoints if Occupancy mode selected -------------------------
if "occupancy" in MODE:
    # Min and max temperature for heating and cooling adaptive for 100% of people [deg C]
    HEAT_TEMP_MAX_100 = 25.7
    HEAT_TEMP_MIN_100 = 18.4
    COOL_TEMP_MAX_100 = 29.7
    COOL_TEMP_MIN_100 = 22.4
    # Furthest setback points allowed when building is unoccupied [deg C]
    vacantCool = 32
    vacantHeat = 12
    
    # Use new np compatible 5min csv datasets. IF they don't exist, create them first - should only need to do that on first run.
    try:
        occ_prob = np.genfromtxt('occupancy_probability_5min.csv', skip_header=startdat+1, max_rows=n, delimiter=',', usecols=1)
        occupancy_status = np.genfromtxt('occupancy_status_5min.csv', skip_header=startdat+1, max_rows=n, delimiter=',', usecols=1)
    except OSError: #If np compatible 5min csv occupancy data does not exist yet, create it using old pd method - Large block of code for error handling for forgetful homo sapiens
        #Initialize dataframe and read occupancy info 
        occupancy_df = pd.read_csv('occupancy_1hr.csv')
        occupancy_df = occupancy_df.set_index('Dates/Times')
        occupancy_df.index = pd.to_datetime(occupancy_df.index)
        # Resample using linear interpolation and export result
        occ_prob_df = occupancy_df.Probability.resample('5min').interpolate(method='linear')
        occ_prob_file = 'occupancy_probability_5min.csv'
        occ_prob_df.to_csv(occ_prob_file, header=True)
        if HRO: print("\nOccupancy Probability Exported to: ", occ_prob_file)
        # Resample with copy beginning of hour value and export result
        occ_stat_df = occupancy_df.Occupancy.resample('5min').pad()
        occ_stat_file = 'occupancy_status_5min.csv'
        occ_stat_df.to_csv(occ_stat_file, header=True)
        if HRO: print("\nOccupancy Status Exported to: ", occ_stat_file)
        occ_prob = np.genfromtxt('occupancy_probability_5min.csv', skip_header=startdat+1, max_rows=n, delimiter=',', usecols=1)
        occupancy_status = np.genfromtxt('occupancy_status_5min.csv', skip_header=startdat+1, max_rows=n, delimiter=',', usecols=1)
    
    if MODE != 'occupancy_sensor':
        if legacy: # Old dataframe method, slow
            # use outdoor temps to get bands where 100% of people are comfortable using lambda functions
            convertOutTemptoCool100 = lambda x: x*0.31 + 19.3   # calculated that 100% band is +/-1.5C 
            convertOutTemptoHeat100 = lambda x: x*0.31 + 16.3
            adaptive_cooling_100 = outdoor_temp_df.apply(convertOutTemptoCool100)
            adaptive_heating_100 = outdoor_temp_df.apply(convertOutTemptoHeat100)
            # When temps too low or too high set to min or max (See adaptive 100)
            adaptive_cooling_100.loc[(adaptive_cooling_100['column1'] < COOL_TEMP_MIN_100)] = COOL_TEMP_MIN_100
            adaptive_cooling_100.loc[(adaptive_cooling_100['column1'] > COOL_TEMP_MAX_100)] = COOL_TEMP_MAX_100
            adaptive_heating_100.loc[(adaptive_heating_100['column1'] < HEAT_TEMP_MIN_100)] = HEAT_TEMP_MIN_100
            adaptive_heating_100.loc[(adaptive_heating_100['column1'] > HEAT_TEMP_MAX_100)] = HEAT_TEMP_MAX_100
            # change from pd dataframe to matrix
            adaptive_cooling_100 = matrix(adaptive_cooling_100.to_numpy())
            adaptive_heating_100 = matrix(adaptive_heating_100.to_numpy())
        else: # New np method, for getWholesaleCAISO and GetWeatherSolar, fast
            adc100 = np.zeros(n)
            adh100 = np.zeros(n)
            for i in range(0,len(outtempnp)):
                adc100[i]=outtempnp[i]*0.31 + 19.8
                if adc100[i] > COOL_TEMP_MAX_100: adc100[i] = COOL_TEMP_MAX_100
                elif adc100[i] < COOL_TEMP_MIN_100: adc100[i] = COOL_TEMP_MIN_100
                adaptive_cooling_100 = matrix(adc100)
                
                adh100[i]= outtempnp[i]*0.31 + 15.8
                if adh100[i] > HEAT_TEMP_MAX_100: adh100[i] = HEAT_TEMP_MAX_100
                elif adh100[i] < HEAT_TEMP_MIN_100: adh100[i] = HEAT_TEMP_MIN_100
                adaptive_heating_100 = matrix(adh100)
            #if HRO_DEBUG:
                #print(adaptive_cooling_100)
                #print(adaptive_heating_100)
        
        # Calculate Occupancy Probability comfort band
        sigma = 3.937 # This was calculated based on adaptive comfort being normally distributed
        
        # Better way to apply comfort bound function 
        fx = np.vectorize(lambda x: (1-x)/2 +1/2)
        fy = np.vectorize(lambda y: norm.ppf(y)*sigma)
        op_comfort_range = fy(fx(occ_prob))
        #print("Numpy op_comfort_range: ", op_comfort_range)
        
        # Added to set back the one that is active, but not oversetback the inactive one because the optimizer will sometimes ridiculously overcool or overheat otherwise. - Not necessary now that -cc fixed
        # if heatorcool == 'heat': 
            # probHeat = adaptive_heating_100-op_comfort_range
            # probCool = adaptiveCool
        # else: #Cool
            # probCool = adaptive_cooling_100+op_comfort_range
            # probHeat = adaptiveHeat
        
        probHeat = adaptive_heating_100-op_comfort_range
        probCool = adaptive_cooling_100+op_comfort_range
        
        # Fixed so don't need this bc legacy resets range elsewhere
        # if legacy:
            # probHeat = adaptive_heating_100[(block-1)*12:(block-1)*12+n,0]-op_comfort_range
            # probCool = adaptive_cooling_100[(block-1)*12:(block-1)*12+n,0]+op_comfort_range
        # else:
            # probHeat = adaptive_heating_100-op_comfort_range
            # probCool = adaptive_cooling_100+op_comfort_range
        #print(probHeat)
        #print(probCool)
    # -- old
    # if MODE == 'occupancy' or MODE == 'occupancy_sensor':   
        # occupancy_status = np.array(occupancy_df.Occupancy.iloc[(block-1)])
        
    # elif MODE == 'occupancy_preschedule':
        # occupancy_status_all = occupancy_df.Occupancy.resample('5min').pad()
        # occupancy_status = np.array(occupancy_status_all.iloc[(block-1)*12:(block-1)*12+n])

#------------------------ Data Ready! -------------------------

# Optimization Setup ----------------------------------------------------------

# Initialize cost
cost = 0

# setting up optimization to minimize energy times price
x=variable(n)    # x is energy usage that we are predicting (Same as E_used on PJs thesis, page 26)

# A matrix is coefficients of energy used variables in constraint equations (same as D in PJs thesis, page 26)
AA = matrix(0.0, (n*2,n))
k = 0
while k<n:
    #This solves dt*C_2 (PJ eq 2.14)
    j = 2*k
    AA[j,k] = timestep*c2
    AA[j+1,k] = -timestep*c2
    k=k+1
k=0
while k<n:
    j=2*k+2
    #Solve C_1 part. row_above * timestep * c1 * 
    while j<2*n-1:
        AA[j,k] = AA[j-2,k]*-timestep*c1+ AA[j-2,k]
        AA[j+1,k] = AA[j-1,k]*-timestep*c1+ AA[j-1,k]
        j+=2
    k=k+1

# Set signs on heat and cool energy ----------------------
# energy is positive for heating
heat_positive = matrix(0.0, (n,n))
i = 0
while i<n:
    # heat_positive is a diagonal matrix with -1 on diagonal. will get multiplied by energy x, which is positive
    heat_positive[i,i] = -1.0 # setting boundary condition: Energy used at each timestep must be greater than 0
    i +=1
    
# energy is negative for cooling
cool_negative = matrix(0.0, (n,n))
i = 0
while i<n:
    # cool_negative is a diagonal matrix with 1 on diagonal. will get multiplied by energy x, which is negative
    cool_negative[i,i] = 1.0 # setting boundary condition: Energy used at each timestep must be less than 0
    i +=1

# Right hand of equality 1 x n matrix of zeros
d = matrix(0.0, (n,1))

# inequality constraints - diagonal matrices <= 0 ------------
heatineq = (heat_positive*x<=d)
#           negative * positive <= [0]
coolineq = (cool_negative*x<=d)
#           positive * negative <= [0]

# Max heating and cooling capacity of HVAC system ------------
energyLimit = matrix(0.25, (n,1)) # .4, 0.25 before
# enforce |E_used[n]| <= E_max
heatlimiteq = (heat_positive * x <= energyLimit)
coollimiteq = (cool_negative * x <= energyLimit)


# creating S matrix to make b matrix simpler -----------------
S = matrix(0.0, (n,1))
S[0,0] = timestep*(c1*(temp_outdoor[0]-temp_indoor_initial)+c3*q_solar[0])+temp_indoor_initial

#Loop to solve all S^(n) for n > 1. Each S value represents the predicted indoor temp at that time
#  based on predictions for previous timestep
i=1
while i<n:
    S[i,0] = timestep*(c1*(temp_outdoor[i]-S[i-1,0])+c3*q_solar[i])+S[i-1,0]
    i+=1

# b matrix ----------------------------------------------------
# b matrix is constant term in constaint equations, containing temperature bounds
b = matrix(0.0, (n*2,1))
# Initialize counter 
k = 0
# Initialize matrices for cool and heat setpoints with flag values. These will contain setpoints for the selected MODE.
spCool = matrix(-999.9, (n,1))
spHeat = matrix(-999.9, (n,1))

# Temperature bounds for b matrix depend on MODE ---------------------------------------------- 
# Loop structure is designed to reduce unnecessary computation and speed up program.  
# Once an "if" or "elif" on that level is true, later "elif"s are ignored.
# This outer occupancy if helps when running adaptive or fixed. If only running occupancy, can remove
# outer if statement and untab the inner ones on your personal copy only. Please keep in master.
if 'occupancy' in MODE:
    # Occupany with both sensor and probability
    if MODE == 'occupancy': #For speed, putting this one first because it is the most common.
        # String for displaying occupancy status
        occnow = ''
        # If occupancy is initially true (occupied)
        if occupancy_status[0] == 1:
            occnow = 'OCCUPIED'
            # Do for the number of timesteps where occupancy is known truth
            while k < n_occ:
                # If occupied initially, use 90% adaptive setpoint for the first occupancy timeframe
                spCool[k,0] = adaptiveCool[k,0]
                spHeat[k,0] = adaptiveHeat[k,0]
                b[2*k,0]=spCool[k]-S[k,0]
                b[2*k+1,0]=-spHeat[k]+S[k,0]
                k = k + 1
        # At this point, k = n_occ = 12 if Occupied,   k = 0 if UNoccupied at t = first_timestep
        # Assume it is UNoccupied at t > first_timestep and use the probabilistic occupancy setpoints
        while k < n:
            spCool[k,0] = probCool[k,0]
            spHeat[k,0] = probHeat[k,0]
            b[2*k,0]=spCool[k]-S[k,0]
            b[2*k+1,0]=-spHeat[k]+S[k,0]
            k = k + 1
        
    elif MODE == 'occupancy_sensor':
        occnow = ''
        # If occupancy is initially true (occupied)
        if occupancy_status[0] == 1:
            occnow = 'OCCUPIED'
            # Do for the number of timesteps where occupancy is known truth
            while k < n_occ:
                # If occupied initially, use 90% adaptive setpoint for the first occupancy frame
                spCool[k,0] = adaptiveCool[k,0]
                spHeat[k,0] = adaptiveHeat[k,0]
                b[2*k,0]=spCool[k]-S[k,0]
                b[2*k+1,0]=-spHeat[k]+S[k,0]
                k = k + 1
        # At this point, k = n_occ = 12 if Occupied,   k = 0 if UNoccupied at t = first_timestep
        # Assume it is UNoccupied at t > first_timestep and use the vacant setpoints. vacantCool and vacantHeat are scalar constants
        while k < n:
            if 'c' in heatorcool:
                spCool[k,0] = vacantCool
                spHeat[k,0] = adaptiveHeat[k,0]
                b[2*k,0]=vacantCool-S[k,0]
                b[2*k+1,0]=-adaptiveHeat[k,0]+S[k,0]
            else: 
                spHeat[k,0] = vacantHeat
                spCool[k,0] = adaptiveCool[k,0]
                b[2*k+1,0]=-vacantHeat+S[k,0]
                b[2*k,0]=adaptiveCool[k,0]-S[k,0]
            k = k + 1
    
    elif MODE == 'occupancy_prob':
        occnow = 'UNKNOWN'
        spCool = probCool
        spHeat = probHeat
        while k<n:
            b[2*k,0]=probCool[k,0]-S[k,0]
            b[2*k+1,0]=-probHeat[k,0]+S[k,0]
            k=k+1
    
    # Infrequently used, so put last for speed
    elif MODE == 'occupancy_preschedule':
        # Create occupancy table
        occnow = '\nTIME\t STATUS \n'
        while k<n:
            # If occupied, use 90% adaptive setpoint
            if occupancy_status[k] == 1:
                spCool[k,0] = adaptiveCool[k,0]
                spHeat[k,0] = adaptiveHeat[k,0]
                b[2*k,0]=adaptiveCool[k,0]-S[k,0]
                b[2*k+1,0]=-adaptiveHeat[k,0]+S[k,0]
                occnow = occnow + str(k) + '\t OCCUPIED\n'
            else: # not occupied, so use probabilistic occupancy setpoints
                spCool[k,0] = vacantCool
                spHeat[k,0] = vacantHeat
                b[2*k,0]=vacantCool-S[k,0]
                b[2*k+1,0]=-vacantHeat+S[k,0]
                occnow = occnow + str(k) + '\t VACANT\n'
            k=k+1
        occnow = occnow + '\n'
    
# Adaptive setpoint without occupancy
elif MODE == 'adaptive90':
    occnow = 'UNKNOWN'
    spCool = adaptiveCool
    spHeat = adaptiveHeat
    while k<n:
        b[2*k,0]=adaptiveCool[k,0]-S[k,0]
        b[2*k+1,0]=-adaptiveHeat[k,0]+S[k,0]
        k=k+1

# Fixed setpoints - infrequently used, so put last
elif MODE == 'fixed':
    # Fixed setpoints:
    FIXED_UPPER = 23.0
    FIXED_LOWER = 20.0
    occnow = 'UNKNOWN'
    while k<n:
        spCool[k,0] = FIXED_UPPER
        spHeat[k,0] = FIXED_LOWER
        b[2*k,0]=FIXED_UPPER-S[k,0]
        b[2*k+1,0]=-FIXED_LOWER+S[k,0]
        k=k+1

elif 'test' in MODE:
    # Fixed setpoints:
    FIXED_UPPER = 50.0
    FIXED_LOWER = 1.0
    occnow = 'UNKNOWN'
    while k<n:
        spCool[k,0] = FIXED_UPPER
        spHeat[k,0] = FIXED_LOWER
        b[2*k,0]=FIXED_UPPER-S[k,0]
        b[2*k+1,0]=-FIXED_LOWER+S[k,0]
        k=k+1

# Human readable output -------------------------------------------------
if HRO:
    print('MODE = ' + MODE)
    print('Date Range: ' + date_range)
    print('Day = ' + str(day))
    print('Block = ' + str(block))
    print('Initial Temperature Inside = ' + str(temp_indoor_initial) + ' deg C')
    print('Initial Temperature Outdoors = ' + str(temp_outdoor[0,0]) + ' deg C')
    print('Initial Max Temp = ' + str(spCool[0,0]) + ' deg C')
    print('Initial Min Temp = ' + str(spHeat[0,0]) + ' deg C\n')
    if occnow == '':
        occnow = 'VACANT'
    print('Current Occupancy Status: ' + occnow)
    # Detect invalid MODE before it breaks optimizer
    if spCool[1,0] == 999.9 or spHeat[1,0] == 999.9:
        print('FATAL ERROR: Invalid mode, check \'MODE\' string. \n\nx x\n >\n _\n')
        print('@ ' + datetime.datetime.today().strftime("%Y-%m-%d_%H:%M:%S") + '   Status: TERMINATING - ERROR')
        print('================================================\n')
        exit()
    print('\nCVXOPT Output: ')

# Final Optimization -------------------------------------------------------------
# Solve for energy at each timestep. Constraint D*E^n_used <= b, D = AA, E^n_used = x
# Cool (rows 0, 2, 4, ...)  D_m[n] * E_used[n] <= T_comfort,upper[n] - S[n]
# Heat (rows 1, 3, 5, ...)  D_m[n] * E_used[n] <= -T_comfort,lower[n] + S[n]
# where  b = T_comfort,upper[n] - S[n] for cooling, or b = -T_comfort,lower[n] + S[n] for heating
ineq = (AA*x <= b)

# For debug only
if HRO_DEBUG:
    print('Before opt \nS = \n')
    print(S)
    print('\nb = \n')
    print(b)
    print('\nMatrix x = \n')
    print(x)
    print('\nMatrix AA = \n')
    print(AA)
    print (ineq)

#Solve comfort zone inequalities 
if 'h' in heatorcool: # PJ eq 2.8; T^(n)_indoor >= T^(n)_comfort,lower
    # Minimize price cc, energy x, with constraint ineq
    lp2 = op(dot(cc,x),ineq)
    op.addconstraint(lp2, heatineq)
    op.addconstraint(lp2,heatlimiteq)
elif 'c' in heatorcool:  # PJ eq 2.9; T^(n)_indoor =< T^(n)_comfort,lower
    lp2 = op(dot(-cc,x),ineq) #maybe because x is negative and price is positive?
    op.addconstraint(lp2, coolineq)
    op.addconstraint(lp2,coollimiteq)
else: #Detect if heat or cool setting is wrong before entering optimizer with invalid data
    print('\nFATAL ERROR: Invalid heat or cool setting, check \'heatorcool\' string. \n\nx x\n >\n _\n')
    print('@ ' + datetime.datetime.today().strftime("%Y-%m-%d_%H:%M:%S") + '   Status: TERMINATING - ERROR')
    print('================================================\n')
    exit()
#Tells CVXOPT to solve the problem
lp2.solve()
# ---------------------- * Solved * -----------------------------


# Catch Primal Infeasibility -------------------------------------------------------
# If primal infeasibility is found (initial temperature is outside of comfort zone, 
# so optimization cannot be done within constriants), set the energy usage to 0 and 
# the predicted indoor temperature to the comfort region bound.
if x.value == None:
    # Warning message
    if HRO: print('\nWARNING: Temperature exceeds bounds, resorting to unoptimized default\n')
    
    #Set energy consumption = 0 because it is not used in this case
    energy = matrix(0.00, (nt,1))
    print('energy consumption')
    j=0
    while j<nt:
        print(energy[j])
        j = j+1
    # Reset indoor temperature setting to defaults
    temp_indoor = matrix(0.0, (nt,1))
    if 'c' in heatorcool: temp_indoor = spCool
    else: temp_indoor = spHeat
    
    # Print output is read by Controller.java. Expect to send 12 of each value
    print('indoor temp prediction')
    j = 0
    while j<nt:
        print(temp_indoor[j,0])
        j = j+1
    
    print('pricing per timestep')
    j = 0
    while j<nt:
        print(cc[j,0])
        j = j+1
    
    print('outdoor temp')
    j = 0
    while j<nt:
        print(temp_outdoor[j,0])
        j = j+1
    
    print('solar radiation')
    j = 0
    while j<nt:
        print(q_solar[j,0])
        j = j+1

    print('heating min')
    j = 0
    while j<nt:
        print(spHeat[j,0])
        j=j+1

    print('cooling max')
    j = 0
    while j<nt:
        print(spCool[j,0])
        j=j+1

    if HRO:
        # Human-readable footer
        print('\n@ ' + datetime.datetime.today().strftime("%Y-%m-%d_%H:%M:%S") + '   Status: TERMINATING WITHOUT OPTIMIZATION')
        print('================================================\n')
    quit() # Stop entire program

# IF Optimization Successful -------------------------------------------------------

# Energy projection is output of CVXOPT
energy = x.value

# For zero-energy (No HVAC) debugging test
if MODE == 'noHVACtest': energy = matrix(0.0, (n,1))

# For preset energy (temp change with energy from EP)
# Change energy matrix as needed
#Current: SAC Jan 1 6am (day 1, hour 6 on SAC Jan 2021 dataset)
if MODE == 'energytest': 
	# Jan 1, 6am
	#energy = matrix([0.555472075, 0.5441195959, 0.8251619103, 0.5680418427, 0.4328863549, 0.7550661865, 0.4663252374, 0.7081681671, 0.5305115967, 0.3080624774, 0.7729041968, 0.6151092013, 0.7972589359, 0.5283601257, 0.7599873747, 0.5120342401, 0.736130555, 0.4923572159, 0.71694203, 0.4722338945, 0.6973660948, 0.04613704547, 0.3618579578, 0.1645973253]) * 0.23;
	# Jan 1, 10am
	#energy = matrix([0.09121830997, 0.08858966966, 0.08618242959, 0.08380415656, 0.08146854992, 0.07914119599, 0.07681455049, 0.07470792546, 0.07267161682, 0.05655640989, 0.05542146523, 0.05428955333, 0.05314928897, 0.05312851111, 0.05223468544, 0.05135956088, 0.05046815455, 0.04959258648, 0.04874667085, 0.04795487004, 0.04717626723, 0.04639286578, 0.04561198501, 0.04482814534]) * 0.8;
	#Jan 2, 18:00
	#energy = matrix([0.05520429313, 0.05538052028, 0.05555830135, 0.05573851872, 0.05592152118, 0.05610777112, 0.05629762551, 0.05649135989, 0.05668917642, 0.05689121117, 0.05709754161, 0.05730819427, 0.05752315216, 0.05755812405, 0.05759906211, 0.05764425567, 0.05769326317, 0.05774597029, 0.05780225201, 0.05786196266, 0.0579249247, 0.05799091936, 0.05805967445, 0.0581308408, 0.05820393366])* 1.15
	#Aug 1, hr 20
	#energy = matrix([0.555472075, 0.5441195959, 0.8251619103, 0.5680418427, 0.4328863549, 0.7550661865, 0.4663252374, 0.7081681671, 0.5305115967, 0.3080624774, 0.7729041968, 0.6151092013, 0.7972589359, 0.5283601257, 0.7599873747, 0.5120342401, 0.736130555, 0.4923572159, 0.71694203, 0.4722338945, 0.6973660948, 0.04613704547, 0.3618579578, 0.1645973253]) * -0.06;
	
	# Aug 1, Hr 11
	#energy = matrix([0.04002503583, 0.04014967304, 0.04101738416, 0.04185218464, 0.04271479738, 0.04358704646, 0.0444693617, 0.04533826292, 0.04622678689, 0.04713516196, 0.04806939897, 0.04903981636, 0.0500348815, 0.2579128691, 0.2543737514, 0.2530070042, 0.252669378, 0.2509320569, 0.2250074834, 0.2041144509, 0.190069227, 0.179677007, 0.1726100605, 0.1655539252]) * -4.6;
	
	# Aug 1, Hr 14
	energy = matrix([0.0485025127, 0.05372523123, 0.05903277688, 0.0643728894, 0.06972699849, 0.07507814259, 0.08041380131, 0.08554385909, 0.09058678983, 0.09555919202, 0.1004573573, 0.1052787466, 0.1100218946, 0.1149173088, 0.1197437616, 0.1245005252, 0.1291870577, 0.1338029559, 0.1383482038, 0.1425751357, 0.1466939817, 0.1506373978, 0.1545810132, 0.1584729039, 0.1622442348]) * -2.4


# Compute optimized predicted future indoor temperature ----------------------------
temp_indoor = matrix(0.0, (n,1))
# Critical bug in optimizer temperature prediction: Constant was swapped in temperature indoors equation for first timestep such that it solved c1(T_out - 0) instead of c1(T_out - T_in)
#temp_indoor[0,0] = timestep*(c1*(temp_outdoor[0,0]-temp_indoor[0,0])+c2*energy[0,0]+c3*q_solar[0,0]) + temp_indoor_initial

temp_indoor[0,0] = timestep*(c1*(temp_outdoor[0,0]-temp_indoor_initial)+c2*energy[0,0]+c3*q_solar[0,0]) + temp_indoor_initial

p = 1
while p<n:
    temp_indoor[p,0] = timestep*(c1*(temp_outdoor[p,0]-temp_indoor[p-1,0])+c2*energy[p,0]+c3*q_solar[p,0])+temp_indoor[p-1,0]
    p = p+1
#temp_indoor = temp_indoor[1:len(temp_indoor),0]
cost = cost + lp2.objective.value()    

# Zero Energy Correction ---------------------------------------------------------------- + Invalid Indoor Temp Prediction Correction
# Problem: if the indoor temperature prediction overestimates the amount of natural heating in heat setting, 
# or natural cooling in cool setting, the optimization results in zero energy consumption. But since we 
# make the setpoints equal to the estimate, EP may not have that natural change, and instead need to run 
# HVAC unnecessarily. Since the optimizer doesn't expect HVAC to run when the energy usage = 0, make the 
# setpoints = comfort zone bounds to prevent this.
if HRO_DEBUG:
    print('Indoor Temperature Prediction Before Zero Energy Correction\n', temp_indoor, '\n')

if 'test' not in MODE:
    p=0
    while p<nt:
        # If energy is near zero, change setpoint to bound spHeat or spCool - Added if temp_indoor outside spCool or spHeat
        if 'c' in heatorcool and (energy[p] > -0.0001 or temp_indoor[p] > spCool[p,0]):
            temp_indoor[p] = spCool[p,0]
        elif 'h' in heatorcool and (energy[p] < 0.0001 or temp_indoor[p] < spHeat[p,0]):
            temp_indoor[p] = spHeat[p,0]
        p = p+1
    if HRO_DEBUG:
        print('Indoor Temperature Setting After Zero Energy Correction\n', temp_indoor, '\n')

# Print output to be read by Controller.java ---------------------------------------------
# Typically send 12 of each value, representing 1 hour.
print('energy consumption')
j=0
while j<n:
    print(energy[j])
    j = j+1

print('indoor temp prediction')
j = 0
while j<n:
    print(temp_indoor[j,0])
    j = j+1

# print('pricing per timestep')
# j = 0
# while j<nt:
    # print(cc[j,0])
    # j = j+1

print('outdoor temp')
j = 0
while j<n:
    print(temp_outdoor[j,0])
    j = j+1

print('solar radiation')
j = 0
while j<n:
    print(q_solar[j,0])
    j = j+1

# print('heating min')
# j = 0
# while j<n:
    # print(spHeat[j,0])
    # j=j+1

# print('cooling max')
# j = 0
# while j<n:
    # print(spCool[j,0])
    # j=j+1

if HRO:
    # Footer for human-readable output
    print('\n@ ' + datetime.datetime.today().strftime("%Y-%m-%d_%H:%M:%S") + '   Status: TERMINATING - OPTIMIZATION SUCCESS')
    print('================================================\n')
