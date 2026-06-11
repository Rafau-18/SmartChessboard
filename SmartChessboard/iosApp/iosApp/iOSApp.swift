import SwiftUI
import Shared

@main
struct iOSApp: App {
    init() {
        AppModulesKt.doInitKoin(config: nil)
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in
                    // OAuth callback deep link (com.smartchessboard://callback, contract §4.2).
                    AuthDeepLinkKt.handleAuthDeeplink(url: url)
                }
        }
    }
}
