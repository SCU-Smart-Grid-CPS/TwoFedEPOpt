# occupancyAdaptSetpoints.py
# Author(s):    Brian Woo-Shem, Kaleb Pattawi, PJ McCurdy
# Version:      1.1  |  Simulation Version: 5.0
# Last Updated: 2021-07-25
# Changelog:
# - Working non-optimizing adaptive and/or occupancy-based setpoints.
# - Code is a forked subset of energyOptTset2hr V5.0
# Usage:
#   Typically run from Controller.java in UCEF energyPlusOpt2Fed or EP_MultipleSims. Will be run again for every hour of simulation.
#   For debugging, can run as python script. In folder where this is stored:
#   ~$ python occupancyAdaptSetpoints.py [Parameters - See "ACCEPT INPUT PARAMETERS" section] 
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

# IMPORTANT PARAMETERS TO CHANGE ------------------------------------------------

# ===> WHEN TO RUN <=== CHECK IT MATCHES EP!!!
# OR can instead designate in [PARAMETERS]
# Options: 
#   'Jan1thru7'
#   'Feb12thru19'
#   'Sep27-Oct3_SJ'
#   'July1thru7'
#   'SummerSJ'
#   'WinterSJ'
#   'bugoff': For debugging and testing specific inputs. Hot w rapid cool off. Run with day = 1, hour = 0.
#   'bugfreeze': Extreme cold values, rapidly gets colder, for testing. Run with day = 1, hour = 0.
#   'bugcook': Extreme hot values, cools briefly then gets hotter for testing. Run with day = 1, hour = 0.
#   'bugsnug': Comfortable 18-23°C for testing both heat and cool.
#   'bugwarm': Less extreme hot than bugcook mode
#   'bugAC': Figure out why cool mode keeps failing if the price changes
#   'bughprice': Analogous to bugAC but for heating with price change
# Make sure to put in single quotes
date_range = 'Jan1thru7' 

# ===> SET HEATING VS COOLING! <===
# OR can instead designate in [PARAMETERS]
#   'heat': only heater, use in winter
#   'cool': only AC, use in summer
heatorcool = 'heat'

# ===> MODE <===
# OR can instead designate in [PARAMETERS]
#   'occupancy': the primary operation mode. Optimization combining probability data and current occupancy status
#   'occupancy_prob': optimization with only occupancy probability (NOT current status)
#   'occupancy_sensor': optimization with only occupancy sensor data for current occupancy status
#   'adaptive90': optimization with adaptive setpoints where 90% people are comfortable. No occupancy
#   'fixed': optimization with fixed setpoints. No occupany.
#   'occupancy_preschedule': Optimize if occupancy status for entire prediction period (2 hrs into future) is known. A joke!?
MODE = 'occupancy'

# ===> Human Readable Output (HRO) SETTING <===
# Extra outputs when testing manually in python or terminal
# These may not be recognized by UCEF Controller.java so HRO = False when running full simulations
HRO = False

# Print HRO Header
if HRO:
    import datetime as datetime
    print()
    print('=========== occupancyAdaptSetpoints.py V1.1 ===========')
    print('@ ' + datetime.datetime.today().strftime("%Y-%m-%d_%H:%M:%S") + '   Status: RUNNING')

# Constants that should not be changed without a good reason --------------------------------

# Temperature Data refresh rate [min]. Typical data with 5 minute intervals use 5. Hourly use 60.
temp_data_interval = 5

# Time constants. Default is set for 1 week.
n= 24 # number of timesteps within prediction windows (24 x 5-min timesteps in 2 hr window)
nt = 12 # Number of effective predicted timesteps
n_occ = 12 # number of timesteps for which occupancy data is considered known (12 = first hour for hourly occupancy data)
timestep = 5*60

# Pricing constants.
PRICING_MULTIPLIER = 4.0
PRICING_OFFSET = 0.10

# ACCEPT INPUT PARAMETERS ----------------------------------------------------------------------
# From UCEF or command line
# Run as:
#           python occupancyAdaptSetpoints.py day hour temp_indoor_initial
#           python occupancyAdaptSetpoints.py day hour temp_indoor_initial n nt
#           python occupancyAdaptSetpoints.py day hour temp_indoor_initial n nt heatorcool
#           python occupancyAdaptSetpoints.py day hour temp_indoor_initial n nt heatorcool MODE
#           python occupancyAdaptSetpoints.py day hour temp_indoor_initial n nt heatorcool MODE date_range
#           python occupancyAdaptSetpoints.py day hour temp_indoor_initial heatorcool
#           python occupancyAdaptSetpoints.py day hour temp_indoor_initial heatorcool MODE
#           python occupancyAdaptSetpoints.py day hour temp_indoor_initial heatorcool MODE date_range
#           python occupancyAdaptSetpoints.py day hour temp_indoor_initial heatorcool nt
#           python occupancyAdaptSetpoints.py day hour temp_indoor_initial n MODE
#           python occupancyAdaptSetpoints.py day hour temp_indoor_initial n MODE date_range
#   Note: Linux may use 'python3' or 'python3.9' instead of 'python'
#         Windows use 'py'
# where:
#   day = [int] day number in simulation. 1 =< day =< [Number of days in simulation]
#   hour = [int] hour of the day. 12am = 0, 12pm = 11, 11pm = 23
#   temp_indoor_initial = [float] the initial indoor temperature in °C
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
                if 8 < len(sys.argv): date_range = sys.argv[8]

# Get data from excel/csv files ------------------------------------------------------
#   All xlsx files have single col of numbers at 5 minute intervals, starting on 2nd row. Only 2nd row and below is detected.

# Get outdoor temps [°C]
if temp_data_interval == 5: # 5 minutes can use directly
    outdoor_temp_df = pd.read_excel('OutdoorTemp.xlsx', sheet_name=date_range,header=0)
    outdoor_temp_df.columns = ['column1']
    temp_outdoor = matrix(outdoor_temp_df.iloc[(block-1)*12:(block-1)*12+n,0].to_numpy())
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

# get solar radiation. Solar.xlsx must be in run directory. Not actually used except to send back to Controller.java
sol_df = pd.read_excel('Solar.xlsx', sheet_name=date_range)
q_solar = matrix(sol_df.iloc[(block-1)*12:(block-1)*12+n,0].to_numpy())

# get wholesale prices. WholesalePrice.xlsx must be in run directory. Not used except to send back
price_df = pd.read_excel('WholesalePrice.xlsx', sheet_name=date_range)
cc=matrix(price_df.iloc[(block-1)*12:(block-1)*12+n,0].to_numpy())*PRICING_MULTIPLIER/1000+PRICING_OFFSET 

# Compute Adaptive Setpoints ---------------------------------------------------------------
# OK to remove if MODE != 'fixed' on your personal version only if the fixed mode is never used. Keep in master
if MODE != 'fixed':
    # Max and min for heating and cooling in adaptive setpoint control for 90% of people [°C]
    HEAT_TEMP_MAX_90 = 26.2
    HEAT_TEMP_MIN_90 = 18.9
    COOL_TEMP_MAX_90 = 30.2
    COOL_TEMP_MIN_90 = 22.9
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
    adaptiveCool = matrix(adaptive_cooling_90.iloc[(block-1)*12:(block-1)*12+n,0].to_numpy())
    adaptiveHeat = matrix(adaptive_heating_90.iloc[(block-1)*12:(block-1)*12+n,0].to_numpy())

# Get Occupancy Data & Compute Setpoints if Occupancy mode selected -------------------------
if "occupancy" in MODE:
    # Min and max temperature for heating and cooling adaptive for 100% of people [°C]
    HEAT_TEMP_MAX_100 = 25.7
    HEAT_TEMP_MIN_100 = 18.4
    COOL_TEMP_MAX_100 = 29.7
    COOL_TEMP_MIN_100 = 22.4
    # Furthest setback points allowed when building is unoccupied [°C]
    vacantCool = 32
    vacantHeat = 12
    
    #Initialize dataframe and read occupancy info 
    occupancy_df = pd.read_csv('occupancy_1hr.csv')
    occupancy_df = occupancy_df.set_index('Dates/Times')
    occupancy_df.index = pd.to_datetime(occupancy_df.index)
    
    if MODE != 'occupancy_sensor':
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
        
        # hourly occupancy probability data to 5 minute intervals
        occ_prob_all = occupancy_df.Probability.resample('5min').interpolate(method='linear')
        
        # Calculate comfort band
        sigma = 3.937 # This was calculated based on adaptive comfort being normally distributed
        #Apply comfort bound function - requires using dataframe to do the lambda
        op_comfort_range = occ_prob_all.iloc[(block-1)*12:(block-1)*12+n].apply(lambda x: (1-x)/2)+1/2
        op_comfort_range = np.array(op_comfort_range.apply(lambda y: norm.ppf(y)*sigma))
        
        probHeat = adaptive_heating_100[(block-1)*12:(block-1)*12+n,0]-op_comfort_range
        probCool = adaptive_cooling_100[(block-1)*12:(block-1)*12+n,0]+op_comfort_range
        
    if MODE == 'occupancy' or MODE == 'occupancy_sensor':   
        occupancy_status = np.array(occupancy_df.Occupancy.iloc[(block-1)])
        
    elif MODE == 'occupancy_preschedule':
        occupancy_status_all = occupancy_df.Occupancy.resample('5min').pad()
        occupancy_status = np.array(occupancy_status_all.iloc[(block-1)*12:(block-1)*12+n])

#------------------------ Data Ready! -------------------------

# Compute Heating & Cooling Setpoints ----------------------------------------------------
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
        if occupancy_status == 1:
            occnow = 'OCCUPIED'
            # Do for the number of timesteps where occupancy is known truth
            while k < n_occ:
                # If occupied initially, use 90% adaptive setpoint for the first occupancy timeframe
                spCool[k,0] = adaptiveCool[k,0]
                spHeat[k,0] = adaptiveHeat[k,0]
                k = k + 1
        # At this point, k = n_occ = 12 if Occupied,   k = 0 if UNoccupied at t = first_timestep
        # Assume it is UNoccupied at t > first_timestep and use the probabilistic occupancy setpoints
        while k < n:
            spCool[k,0] = probCool[k,0]
            spHeat[k,0] = probHeat[k,0]
            k = k + 1
        
    elif MODE == 'occupancy_sensor':
        occnow = ''
        # If occupancy is initially true (occupied)
        if occupancy_status == 1:
            occnow = 'OCCUPIED'
            # Do for the number of timesteps where occupancy is known truth
            while k < n_occ:
                # If occupied initially, use 90% adaptive setpoint for the first occupancy frame
                spCool[k,0] = adaptiveCool[k,0]
                spHeat[k,0] = adaptiveHeat[k,0]
                k = k + 1
        # At this point, k = n_occ = 12 if Occupied,   k = 0 if UNoccupied at t = first_timestep
        # Assume it is UNoccupied at t > first_timestep and use the vacant setpoints. vacantCool and vacantHeat are scalar constants
        while k < n:
            spCool[k,0] = vacantCool
            spHeat[k,0] = vacantHeat
            k = k + 1
    
    elif MODE == 'occupancy_prob':
        occnow = 'UNKNOWN'
        spCool = probCool
        spHeat = probHeat
    
    # Infrequently used, so put last for speed
    elif MODE == 'occupancy_preschedule':
        # Create occupancy table
        occnow = '\nTIME\t STATUS \n'
        while k<n:
            # If occupied, use 90% adaptive setpoint
            if occupancy_status[k] == 1:
                spCool[k,0] = adaptiveCool[k,0]
                spHeat[k,0] = adaptiveHeat[k,0]
                occnow = occnow + str(k) + '\t OCCUPIED\n'
            else: # not occupied, so use probabilistic occupancy setpoints
                spCool[k,0] = vacantCool
                spHeat[k,0] = vacantHeat
                occnow = occnow + str(k) + '\t VACANT\n'
            k=k+1
        occnow = occnow + '\n'
    
# Adaptive setpoint without occupancy
elif MODE == 'adaptive90':
    occnow = 'UNKNOWN'
    spCool = adaptiveCool
    spHeat = adaptiveHeat

# Fixed setpoints - infrequently used, so put last
elif MODE == 'fixed':
    # Fixed setpoints:
    FIXED_UPPER = 23.0
    FIXED_LOWER = 20.0
    occnow = 'UNKNOWN'
    while k<n:
        spCool[k,0] = FIXED_UPPER
        spHeat[k,0] = FIXED_LOWER
        k=k+1

# Human readable output -------------------------------------------------
if HRO:
    print('MODE = ' + MODE)
    print('Date Range: ' + date_range)
    print('Day = ' + str(day))
    print('Block = ' + str(block))
    print('Initial Temperature Inside = ' + str(temp_indoor_initial) + ' °C')
    print('Initial Temperature Outdoors = ' + str(temp_outdoor[0,0]) + ' °C')
    print('Initial Max Temp = ' + str(spCool[0,0]) + ' °C')
    print('Initial Min Temp = ' + str(spHeat[0,0]) + ' °C\n')
    if occnow == '':
        occnow = 'VACANT'
    print('Current Occupancy Status: ' + occnow)
    # Detect invalid MODE
    if spCool[1,0] == 999.9 or spHeat[1,0] == 999.9:
        print('FATAL ERROR: Invalid mode, check \'MODE\' string. \n\nx x\n >\n ⁔\n')
        print('@ ' + datetime.datetime.today().strftime("%Y-%m-%d_%H:%M:%S") + '   Status: TERMINATING - ERROR')
        print('================================================\n')
        exit()


#Set and return energy consumption = 0 because it is not used in this case
energy = matrix(0.00, (nt,1))
print('energy consumption')
j=0
while j<nt:
    print(energy[j])
    j = j+1

# Set indoor temperature to the setpoints depending on heat vs cool mode
temp_indoor = matrix(0.0, (nt,1))

if heatorcool == 'cool':
    temp_indoor = spCool
else: #heat
    temp_indoor = spHeat

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
    print('\n@ ' + datetime.datetime.today().strftime("%Y-%m-%d_%H:%M:%S") + '   Status: TERMINATING - SETPOINTS SUCCESS')
    print('================================================\n')
