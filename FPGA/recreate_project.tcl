# Vivado TCL script to recreate the project in any version
# Run this from the command line: vivado -mode tcl -source recreate_project.tcl
# Or from Vivado TCL console: source recreate_project.tcl

# Define project name and path
set proj_name "FPGA"
set proj_dir "."

# Create the project
create_project $proj_name $proj_dir -part xc7s25csga324-1 -force
set_property board_part digilentinc.com:arty-s7-25:part0:1.1 [current_project]
set_property target_language VHDL [current_project]

# Add source files
add_files -norecurse -fileset sources_1 "./FPGA.srcs/sources_1/new/top.vhd"
set_property top pulser_rtz [current_fileset]

# Add constraints
add_files -norecurse -fileset constrs_1 "./FPGA.srcs/constrs_1/new/constraints.xdc"

update_compile_order -fileset sources_1
puts "Project recreated successfully!"
