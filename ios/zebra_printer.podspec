#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
#
Pod::Spec.new do |s|
  s.name             = 'zebra_printer'
  s.version          = '0.0.1'
  s.summary          = 'A Flutter plugin for Zebra printers.'
  s.description      = <<-DESC
A Flutter plugin for Zebra printers that supports both Android and iOS.
                       DESC
  s.homepage         = 'http://example.com'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Your Company' => 'email@example.com' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*'
  s.public_header_files = 'Classes/**/*.h'
  s.dependency 'Flutter'
  s.platform = :ios, '11.0'
  
  # Zebra SDK header paths
  s.xcconfig = { 
    'HEADER_SEARCH_PATHS' => '$(PODS_ROOT)/../../Classes/include',
    'LIBRARY_SEARCH_PATHS' => '$(PODS_ROOT)/../../Classes'
  }
  
  # Include Zebra SDK static library
  s.vendored_libraries = 'Classes/libZSDK_API.a'
  
  # Frameworks
  s.frameworks = 'ExternalAccessory', 'CoreBluetooth'
  
  # Swift version
  s.swift_version = '5.0'
  
  # Include paths
  s.pod_target_xcconfig = {
    'HEADER_SEARCH_PATHS' => '$(PODS_ROOT)/../../Classes/include',
    'USER_HEADER_SEARCH_PATHS' => '$(PODS_ROOT)/../../Classes/include'
  }
end