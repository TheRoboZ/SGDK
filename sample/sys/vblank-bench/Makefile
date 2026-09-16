# makefile.gen references $(INC_LIB), which common.mk never defines (it defines
# INCLUDE_LIB). Without this every sample fails with "genesis.h: No such file or
# directory". Supply it here instead of patching the shared build files.
GDK ?= ../../..
INC_LIB := $(GDK)/inc

include $(GDK)/makefile.gen
