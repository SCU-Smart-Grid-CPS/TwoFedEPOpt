# File:     occupancy_status_generator.py
# Author:   Brian Woo-Shem
# Updated:  2021-06-30
# Version:  4.1
# Notes:    Run in same folder as occupancy_1hr.csv
#           Generates a file called "occ_status_YYYY-MM-DD_HH-MM.csv"

import random
from cvxopt import matrix, solvers
import csv
import datetime
import pandas as pd
import numpy

# get occupancy data from csv. CHANGE FILENAME if needed. Put file in same directory!
occ_prob = pd.read_csv('occupancy_1hr.csv',usecols=['Probability'])

# Set n = number of 1 hour timesteps we want
#n = 72
n = len(occ_prob)

#Convert to regular matrix
op = matrix(occ_prob['Probability'].to_numpy())

# Create two vectors of type matrix to hold the occupancy status and 
occ_st = matrix(0, (n,1))
ran = matrix(0.0, (n,1))

fields = ['Probability', 'random_num', 'occ_result']

for c in range(0,n): 
    # generate random num on [0,1]
    r = random.random()
    # when probability is larger than random, is occupied
    if op[c,0] > r:
        occ_st[c,0] = 1
    # when probability is smaller than random num, not occupied
    else:
        occ_st[c,0] = 0
    # save the random number
    ran[c,0] = r

# Single matrix holding all values, index is the timestep.
# Only needed if using with optimizer code directly, then would remove the write to csv part.
# ostat = [[op], [ran], [occ_rt]]
    

# write to csv
# Create file with time/date based name
filename = "occ_status_" + datetime.datetime.today().strftime("%Y-%m-%d_%H-%M") + ".csv"
print("Occupancy Status saved as: " + filename)
with open(filename, 'w') as csvfile:
    #create csv writer object
    csvwrite = csv.writer(csvfile)
    
    #add fields (labels) to top row
    csvwrite.writerow(fields)
    
    #works but has annoying extra line break:
    #csvwrite.writerows(zip(op,ran,occ_rt)))

    #Works correctly
    for x in zip(op,ran,occ_st):
        csvfile.write("{0},{1},{2}\n".format(*x))
