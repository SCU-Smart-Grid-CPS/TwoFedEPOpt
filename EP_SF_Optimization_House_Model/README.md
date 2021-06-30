#Energy Plus San Francisco House Model
For optimization with a single house in EP and the EnergyPlusOpt2Fed federation in UCEF.

Tested to work on the cluster, with EP 9-4-0 and UCEF 1.1.0

To run, check the .idf run period and match with the one set in the UCEF federation. Change ipconfig.txt in the folder Joe_ep_fmu. Then zip Binaries, ipconfig.txt, and modelDescription.xml and change file extension, saving this as "Joe_ep_fmu.fmu"
Move the .fmu to the same directory as the .idf

Note the included Joe_ep_fmu.fmu will likely not work because the ipconfig is wrong for your computer. 