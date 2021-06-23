# TwoFedEPOpt
Optimization in Python connected to controller UCEF federate.   
Socket UCEF federate connects to EnergyPlus simulation. Compatible with simulations in https://github.com/SCU-Smart-Grid-CPS/ep-multiple-sims

### Run Instructions
* Change filepaths in Controller.java and Socket.java
* Check weather and pricing spreadsheet dates in Controller.java and energyOptTset.py
* Comment out relevant sections in Controller.java depending on Fixed vs Adaptive and Optimized vs Not Optimized
* Compile Controller.java and Socket.java
* See https://github.com/SCU-Smart-Grid-CPS/smart-grid-energy-simulation-research/wiki for UCEF operation.

### Changelog
2021-06-23 (Brian)
* added easier switching between adaptive and fixed setpoint methods in energyOptTset.py
* verified to work on cluster
* removed obsolete files to avoid confusion
* various updates since previous uploaded version
* commenting and readability significantly improved