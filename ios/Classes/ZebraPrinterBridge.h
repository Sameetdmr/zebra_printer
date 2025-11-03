#ifndef ZebraPrinterBridge_h
#define ZebraPrinterBridge_h

#import <Foundation/Foundation.h>
#import "ZebraPrinterConnection.h"
#import "TcpPrinterConnection.h"
#import "MfiBtPrinterConnection.h"
#import "ZebraPrinter.h"
#import "ZebraPrinterFactory.h"
#import "PrinterStatus.h"
#import "SGD.h"

// Declare C functions for Swift
#ifdef __cplusplus
extern "C" {
#endif

// Connection functions
void* TcpPrinterConnection_new(const char* address, int port);
void* MfiBtPrinterConnection_new(const char* serialNumber);
BOOL ZebraPrinterConnection_open(void* connection);
int ZebraPrinterConnection_write(void* connection, const unsigned char* data, int length);
void ZebraPrinterConnection_close(void* connection);
BOOL ZebraPrinterConnection_isConnected(void* connection);

// Printer functions
void* ZebraPrinterFactory_getInstance(void* connection);
void* ZebraPrinter_getCurrentStatus(void* printer);

// Status functions
BOOL PrinterStatus_isHeadOpen(void* status);
BOOL PrinterStatus_isPaperOut(void* status);
BOOL PrinterStatus_isPaused(void* status);

// SGD functions
char* SGD_GET(void* device, const char* setting);

#ifdef __cplusplus
}
#endif

#endif /* ZebraPrinterBridge_h */