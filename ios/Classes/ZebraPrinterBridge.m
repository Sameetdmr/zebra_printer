#import <Foundation/Foundation.h>
#import "ZebraPrinterBridge.h"

// This file is used to bridge between Objective-C and Swift.
// It makes the C-based APIs of the Zebra SDK accessible from Swift.

// Connection functions
void* TcpPrinterConnection_new(const char* address, int port) {
    NSString *ipAddress = [NSString stringWithUTF8String:address];
    TcpPrinterConnection *connection = [[TcpPrinterConnection alloc] initWithAddress:ipAddress andWithPort:port];
    return (__bridge_retained void*)connection;
}

void* MfiBtPrinterConnection_new(const char* serialNumber) {
    NSString *serial = [NSString stringWithUTF8String:serialNumber];
    MfiBtPrinterConnection *connection = [[MfiBtPrinterConnection alloc] initWithSerialNumber:serial];
    return (__bridge_retained void*)connection;
}

BOOL ZebraPrinterConnection_open(void* connection) {
    id<ZebraPrinterConnection> conn = (__bridge id<ZebraPrinterConnection>)connection;
    return [conn open];
}

int ZebraPrinterConnection_write(void* connection, const unsigned char* data, int length) {
    id<ZebraPrinterConnection> conn = (__bridge id<ZebraPrinterConnection>)connection;
    NSData *nsData = [NSData dataWithBytes:data length:length];
    NSError *error = nil;
    return [conn write:nsData error:&error];
}

void ZebraPrinterConnection_close(void* connection) {
    id<ZebraPrinterConnection> conn = (__bridge_transfer id<ZebraPrinterConnection>)connection;
    [conn close];
}

BOOL ZebraPrinterConnection_isConnected(void* connection) {
    id<ZebraPrinterConnection> conn = (__bridge id<ZebraPrinterConnection>)connection;
    return [conn isConnected];
}

// Printer functions
void* ZebraPrinterFactory_getInstance(void* connection) {
    id<ZebraPrinterConnection> conn = (__bridge id<ZebraPrinterConnection>)connection;
    NSError *error = nil;
    id<ZebraPrinter> printer = [ZebraPrinterFactory getInstance:conn error:&error];
    return (__bridge_retained void*)printer;
}

void* ZebraPrinter_getCurrentStatus(void* printer) {
    id<ZebraPrinter> printerObj = (__bridge id<ZebraPrinter>)printer;
    NSError *error = nil;
    PrinterStatus *status = [printerObj getCurrentStatus:&error];
    return (__bridge_retained void*)status;
}

// Status functions
BOOL PrinterStatus_isHeadOpen(void* status) {
    PrinterStatus *statusObj = (__bridge PrinterStatus*)status;
    return [statusObj isHeadOpen];
}

BOOL PrinterStatus_isPaperOut(void* status) {
    PrinterStatus *statusObj = (__bridge PrinterStatus*)status;
    return [statusObj isPaperOut];
}

BOOL PrinterStatus_isPaused(void* status) {
    PrinterStatus *statusObj = (__bridge PrinterStatus*)status;
    return [statusObj isPaused];
}

// SGD functions
char* SGD_GET(void* device, const char* setting) {
    id<ZebraPrinterConnection> conn = (__bridge id<ZebraPrinterConnection>)device;
    NSString *settingStr = [NSString stringWithUTF8String:setting];
    NSError *error = nil;
    NSString *result = [SGD GET:settingStr withPrinterConnection:conn error:&error];
    if (error || !result) {
        return NULL;
    }
    return strdup([result UTF8String]);
}

// SGD_SET is removed as it's not currently used
