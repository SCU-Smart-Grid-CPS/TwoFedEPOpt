# Add commenting

# To do:
# remove heatorcool
# E =0, T = comfort zone
# mode: Adaptive vs fixed
# send both heating and cooling setpoints
# ensure timing is right



# import packages 
from cvxopt import matrix, solvers
from cvxopt.modeling import op, dot, variable
import time
import pandas as pd
import sys

# Paramters to change (Config File)
timestep = 5*60 # number of seconds per timestep
n=24 # number of timesteps within prediction windows (24 x 5-min timesteps in 2 hr window)
pricingmultfactor = 4.0
pricingoffset = 0.10
adaptive_comfort = False
occupancy_mode = False
date_range = 'Jan1thru7'   #'July1thru7' Jan1thru7 Feb12thru19 Sept27thruOct3 July1thru7

heatorcool = 'cool'

# Max and min for fixed setpoint control
comfortZone_upper = 23.0
comfortZone_lower = 20.0
# Max and min for heating and cooling in adaptive setpoint control
heatTempMax = 26.2
heatTempMin = 18.9
coolTempMax = 30.2
coolTempMin = 22.9
# Max and min for heating and cooling in occupancy control
noOccupancyHeat = 12
noOccupancyCool = 32
# constant coefficients for indoor temperature equation
c1 = 1.72*10**-5
if heatorcool == 'heat':
	c2 = 0.0031
else:
	c2 = -0.0031
c3 = 3.58*10**-7

# get inputs from UCEF --------------------------------------------
day = int(sys.argv[1]) 
hour = int(sys.argv[2]) +1+(day-1)*24 # hour goes 0:23 (represents the hour within a day)
temp_indoor_initial = float(sys.argv[3]) # current indoor temperature from EP

# Get data from excel/csv files --------------------------------------------
# Get outdoor temps
df = pd.read_excel('OutdoorTemp.xlsx', sheet_name=date_range,header=0)
# print(df.head())
temp_outdoor_all=matrix(df.to_numpy())
df.columns = ['column1']
# use outdoor temps to get adaptive setpoints using lambda functions
convertOutTemptoCoolTemp = lambda x: x*0.31 + 19.8
convertOutTemptoHeatTemp = lambda x: x*0.31 + 15.8
adaptive_cooling_setpoints = df.apply(convertOutTemptoCoolTemp)
adaptive_heating_setpoints = df.apply(convertOutTemptoHeatTemp)
# print(adaptive_heating_setpoints)
# When temps too low or too high set to min or max (See adaptive setpoints)
adaptive_cooling_setpoints.loc[(adaptive_cooling_setpoints['column1'] < coolTempMin)] = coolTempMin
adaptive_cooling_setpoints.loc[(adaptive_cooling_setpoints['column1'] > coolTempMax)] = coolTempMax
adaptive_heating_setpoints.loc[(adaptive_heating_setpoints['column1'] < heatTempMin)] = heatTempMin
adaptive_heating_setpoints.loc[(adaptive_heating_setpoints['column1'] > heatTempMax)] = heatTempMax
# change from pd dataframe to matrix
# print(adaptive_heating_setpoints)
adaptive_cooling_setpoints = matrix(adaptive_cooling_setpoints.to_numpy())
adaptive_heating_setpoints = matrix(adaptive_heating_setpoints.to_numpy())
# print(adaptive_heating_setpoints)

# get occupancy data
occupancy_df = pd.read_csv('occupancy_1hr.csv')
occupancy = matrix(occupancy_df['Occupancy'].to_numpy())

# get solar radiation
df = pd.read_excel('Solar.xlsx', sheet_name=date_range)
q_solar_all=matrix(df.to_numpy())

# get wholesale prices
df = pd.read_excel('WholesalePrice.xlsx', sheet_name='Feb12thru19')  # CHANGE TO MATCH CURRENT SIM!  # 'Jan1thru7'
wholesaleprice_all=matrix(df.to_numpy())

# setting up optimization to minimize energy times price
x=variable(n)	# x is energy usage that we are predicting

# A matrix is coefficients of energy used variables in constraint equations (see PJs equations)
AA = matrix(0.0, (n*2,n))
k = 0
while k<n:
	j = 2*k
	AA[j,k] = timestep*c2
	AA[j+1,k] = -timestep*c2
	k=k+1
k=0
while k<n:
	j=2*k+2
	while j<2*n-1:
		AA[j,k] = AA[j-2,k]*-timestep*c1+ AA[j-2,k]
		AA[j+1,k] = AA[j-1,k]*-timestep*c1+ AA[j-1,k]
		j+=2
	k=k+1

# making sure energy is positive for heating
heat_positive = matrix(0.0, (n,n))
i = 0
while i<n:
	heat_positive[i,i] = -1.0 # setting boundary condition: Energy used at each timestep must be greater than 0
	i +=1
# making sure energy is negative for cooling
cool_negative = matrix(0.0, (n,n))
i = 0
while i<n:
	cool_negative[i,i] = 1.0 # setting boundary condition: Energy used at each timestep must be less than 0
	i +=1

d = matrix(0.0, (n,1))
# inequality constraints
heatineq = (heat_positive*x<=d)
coolineq = (cool_negative*x<=d)
energyLimit = matrix(0.25, (n,1)) # .4, 0.25 before
heatlimiteq = (cool_negative*x<=energyLimit)
coollimiteq = (heat_positive*x<=0.1)

# creating S matrix to make b matrix simpler
temp_outdoor= temp_outdoor_all[(hour-1)*12:(hour-1)*12+n,0] # getting next two hours of data
q_solar=q_solar_all[(hour-1)*12:(hour-1)*12+n,0]

# get price for next two hours
cc=wholesaleprice_all[(hour-1)*12:(hour-1)*12+n,0]*pricingmultfactor/1000+pricingoffset
# cc = c[(hour-1)*12:(hour-1)*12+n,0]
S = matrix(0.0, (n,1))
S[0,0] = timestep*(c1*(temp_outdoor[0]-temp_indoor_initial)+c3*q_solar[0])+temp_indoor_initial

i=1
while i<n:
	S[i,0] = timestep*(c1*(temp_outdoor[i]-S[i-1,0])+c3*q_solar[i])+S[i-1,0]
	i+=1
#print(S)

# b matrix is constant term in constaint equations
b = matrix(0.0, (n*2,1))

# k = 0
# while k<n:
# 	b[2*k,0]=comfortZone_upper-S[k,0]
# 	b[2*k+1,0]=-comfortZone_lower+S[k,0]
# 	k=k+1

adaptiveHeat = adaptive_heating_setpoints[(hour-1)*12:(hour-1)*12+n,0]
adaptiveCool = adaptive_cooling_setpoints[(hour-1)*12:(hour-1)*12+n,0]

if occupancy_mode == True:
	occupancy_Checker = occupancy[(hour-1)*12:(hour-1)*12+n,0]
	k = 0
	while k<n:
		if occupancy_Checker[k,0] ==1:
			b[2*k,0]=adaptiveCool[k,0]-S[k,0]
			b[2*k+1,0]=-adaptiveHeat[k,0]+S[k,0]
		else:
			b[2*k,0]=noOccupancyCool-S[k,0]
			b[2*k+1,0]=-noOccupancyHeat+S[k,0]
		k=k+1
else:
	k = 0
	while k<n:
		b[2*k,0]=adaptiveCool[k,0]-S[k,0]
		b[2*k+1,0]=-adaptiveHeat[k,0]+S[k,0]
		k=k+1
# else:
# 	k = 0
# 	while k<n:
# 		b[2*k,0]=comfortZone_upper-S[k,0]
# 		b[2*k+1,0]=-comfortZone_lower+S[k,0]
# 		k=k+1

# time to solve for energy at each timestep
#print(cc)
#print(AA)
#print(b)
ineq = (AA*x <= b)
if heatorcool == 'heat':
	lp2 = op(dot(cc,x),ineq)
	op.addconstraint(lp2, heatineq)
	op.addconstraint(lp2,heatlimiteq)
if heatorcool == 'cool':
	lp2 = op(dot(-cc,x),ineq)
	op.addconstraint(lp2, coolineq)
	op.addconstraint(lp2,coollimiteq)
lp2.solve()

# If primal infeasibility is found (optimization cannot be done within constriants), set the energy usage to 0 and the predicted indoor temperature to the comfort region bound.
if x.value == None:
	# if heatorcool == 'heat':
	# 	lp2 = op(dot(-cc,x),ineq)
	# 	op.addconstraint(lp2, coolineq)
	# 	op.addconstraint(lp2,coollimiteq)
	# if heatorcool == 'cool':
	# 	lp2 = op(dot(-cc,x),ineq)
	# 	op.addconstraint(lp2,heatineq)
	# 	op.addconstraint(lp2, heatlimiteq)
	# lp2.solve()
	if x.value == None:
		energy = matrix(0.00, (13,1))
		print('energy consumption')
		j=0
		while j<13:
			print(energy[j])
			j = j+1
		j=0
		# temp_indoor = matrix(0.0, (n,1))
		# temp_indoor[0,0] = temp_indoor_initial
		# p = 1
		# while p<13:
		# 	temp_indoor[p,0] = timestep*(c1*(temp_outdoor[p-1,0]-temp_indoor[p-1,0])+c2*energy[p-1,0]+c3*q_solar[p-1,0])+temp_indoor[p-1,0]
		# 	p = p+1
		temp_indoor = matrix(0.0, (13,1))
		while j<13:
			if heatorcool == 'cool':
				temp_indoor[j]=adaptiveCool[j,0]
			else:
				temp_indoor[j]=adaptiveHeat[j,0]
			# if heatorcool == 'cool':
			# 	temp_indoor[j]=comfortZone_upper
			# else:
			# 	temp_indoor[j]=comfortZone_lower
			j=j+1
		print('indoor temp prediction')
		j = 0
		while j<13:
			print(temp_indoor[j,0])
			j = j+1

		print('pricing per timestep')
		j = 0
		while j<13:
			print(cc[j,0])
			j = j+1

		print('outdoor temp')
		j = 0
		while j<13:
			print(temp_outdoor[j,0])
			j = j+1
		print('solar radiation')
		j = 0
		while j<13:
			print(q_solar[j,0])
			j = j+1
		print('adaptive heating setpoints')
		j = 0
		while j<12:
			print(adaptiveHeat[j,0])
			j=j+1
		print('adaptive cooling setpoints')
		j = 0
		while j<12:
			print(adaptiveCool[j,0])
			j=j+1
		quit()

energy = x.value
# print('energy value found')

# print(lp2.objective.value())
temp_indoor = matrix(0.0, (n,1))
temp_indoor[0,0] = temp_indoor_initial
p = 1
while p<n:
	temp_indoor[p,0] = timestep*(c1*(temp_outdoor[p-1,0]-temp_indoor[p-1,0])+c2*energy[p-1,0]+c3*q_solar[p-1,0])+temp_indoor[p-1,0]
	p = p+1
# print('indoor temp calculated 1')
p=1
while p<n:
	if heatorcool == 'cool':
		if energy[p] > -0.0001:
			# temp_indoor[p] = comfortZone_upper
			temp_indoor[p] = adaptiveCool[p]
	else:
		# if round(energy[p],3) == 0.0:
		if energy[p] < 0.0001:
			# temp_indoor[p] = comfortZone_lower
			temp_indoor[p]=adaptiveHeat[p,0]
	p = p+1
		# print('indoor temp adjusted' p)

print('energy consumption')
j=0
while j<13:
	print(energy[j])
	j = j+1

print('indoor temp prediction')
j = 0
while j<13:
	print(temp_indoor[j,0])
	j = j+1

print('pricing per timestep')
j = 0
while j<13:
	print(cc[j,0])
	j = j+1

print('outdoor temp')
j = 0
while j<13:
	print(temp_outdoor[j,0])
	j = j+1
print('solar radiation')
j = 0
while j<13:
	print(q_solar[j,0])
	j = j+1
print('adaptive heating setpoints')
j = 0
while j<12:
	print(adaptiveHeat[j,0])
	j=j+1
print('adaptive cooling setpoints')
j = 0
while j<12:
	print(adaptiveCool[j,0])
	j=j+1
