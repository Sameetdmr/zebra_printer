#import "ZebraPrinterPlugin.h"
#if __has_include(<zebra_printer/zebra_printer-Swift.h>)
#import <zebra_printer/zebra_printer-Swift.h>
#else
#import "zebra_printer-Swift.h"
#endif

@implementation ZebraPrinterPlugin
+ (void)registerWithRegistrar:(NSObject<FlutterPluginRegistrar>*)registrar {
  [SwiftZebraPrinterPlugin registerWithRegistrar:registrar];
}
@end

