import UIKit
import Capacitor

/// Registrerar appens egna plugins (motsvarar MainActivity.java på Android).
class FitchiViewController: CAPBridgeViewController {
    override open func capacitorDidLoad() {
        bridge?.registerPluginInstance(FitchiHalsaPlugin())
    }
}
