//
//  SimulatorTunnelProvider.swift
//  MullvadVPN
//
//  Created by pronebird on 05/02/2020.
//  Copyright © 2020 Mullvad VPN AB. All rights reserved.
//


import Foundation
import NetworkExtension

// MARK: - Formal conformances

protocol VPNConnectionProtocol: NSObject {
    var status: NEVPNStatus { get }

    func startVPNTunnel() throws
    func startVPNTunnel(options: [String: NSObject]?) throws
    func stopVPNTunnel()
}

protocol VPNTunnelProviderSessionProtocol {
    func sendProviderMessage(_ messageData: Data, responseHandler: ((Data?) -> Void)?) throws
}

protocol VPNTunnelProviderManagerProtocol: Equatable {
    associatedtype SelfType: VPNTunnelProviderManagerProtocol
    associatedtype ConnectionType: VPNConnectionProtocol

    var isEnabled: Bool { get set }
    var protocolConfiguration: NEVPNProtocol? { get set }
    var localizedDescription: String? { get set }
    var connection: ConnectionType { get }

    init()

    func loadFromPreferences(completionHandler: @escaping (Error?) -> Void)
    func saveToPreferences(completionHandler: ((Error?) -> Void)?)
    func removeFromPreferences(completionHandler: ((Error?) -> Void)?)

    static func loadAllFromPreferences(completionHandler: @escaping ([SelfType]?, Error?) -> Void)
}

extension NEVPNConnection: VPNConnectionProtocol {}
extension NETunnelProviderSession: VPNTunnelProviderSessionProtocol {}
extension NETunnelProviderManager: VPNTunnelProviderManagerProtocol {}

#if targetEnvironment(simulator)

// MARK: - NEPacketTunnelProvider stubs

protocol SimulatorTunnelProviderDelegate {
    func startTunnel(options: [String: Any]?, completionHandler: @escaping (Error?) -> Void)
    func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void)
    func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?)
}

class SimulatorTunnelProvider {
    static let shared = SimulatorTunnelProvider()

    private let lock = NSLock()
    private var _delegate: SimulatorTunnelProviderDelegate?

    var delegate: SimulatorTunnelProviderDelegate! {
        get {
            lock.withCriticalBlock { _delegate }
        }
        set {
            lock.withCriticalBlock {
                _delegate = newValue
            }
        }
    }

    private init() {}

    fileprivate func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)? = nil) {
        self.delegate.handleAppMessage(messageData, completionHandler: completionHandler)
    }
}

// MARK: - NEVPNConnection stubs

class SimulatorVPNConnection: NSObject, VPNConnectionProtocol {

    private let lock = NSRecursiveLock()

    private var _status: NEVPNStatus = .disconnected
    private(set) var status: NEVPNStatus {
        get {
            lock.withCriticalBlock { _status }
        }
        set {
            lock.withCriticalBlock {
                if newValue != _status {
                    _status = newValue

                    // Send notification while holding the lock. This should enable the receiver
                    // to fetch the `SimulatorVPNConnection.status` before it changes.
                    postStatusDidChangeNotification()
                }
            }
        }
    }

    func startVPNTunnel() throws {
        try startVPNTunnel(options: nil)
    }

    func startVPNTunnel(options: [String: NSObject]?) throws {
        status = .connecting

        SimulatorTunnelProvider.shared.delegate.startTunnel(options: options) { (error) in
            if error == nil {
                self.status = .connected
            } else {
                self.status = .disconnected
            }
        }
    }

    func stopVPNTunnel() {
        status = .disconnecting

        SimulatorTunnelProvider.shared.delegate.stopTunnel(with: .none) {
            self.status = .disconnected
        }
    }

    private func postStatusDidChangeNotification() {
        NotificationCenter.default.post(name: .NEVPNStatusDidChange, object: self)
    }
}

// MARK: - NETunnelProviderSession stubs

class SimulatorTunnelProviderSession: SimulatorVPNConnection, VPNTunnelProviderSessionProtocol {

    func sendProviderMessage(_ messageData: Data, responseHandler: ((Data?) -> Void)?) throws {
        SimulatorTunnelProvider.shared.handleAppMessage(messageData, completionHandler: responseHandler)
    }

}

// MARK: - NETunnelProviderManager stubs

/// A mock struct for tunnel configuration and connection
private struct SimulatorTunnelInfo {
    /// A unique identifier for the configuration
    var identifier = UUID().uuidString

    /// An associated VPN connection.
    /// Intentionally initialized with a `SimulatorTunnelProviderSession` subclass which
    /// implements the necessary protocol
    var connection: SimulatorVPNConnection = SimulatorTunnelProviderSession()

    /// Whether configuration is enabled
    var isEnabled = false

    /// Protocol configuration
    var protocolConfiguration: NEVPNProtocol?

    /// Tunnel description
    var localizedDescription: String?
}

class SimulatorTunnelProviderManager: VPNTunnelProviderManagerProtocol, Equatable {

    static let tunnelsLock = NSRecursiveLock()
    fileprivate static var tunnels = [SimulatorTunnelInfo]()

    private let lock = NSLock()
    private var tunnelInfo: SimulatorTunnelInfo
    private var identifier: String {
        lock.withCriticalBlock { tunnelInfo.identifier }
    }

    var isEnabled: Bool {
        get {
            lock.withCriticalBlock { tunnelInfo.isEnabled }
        }
        set {
            lock.withCriticalBlock {
                tunnelInfo.isEnabled = newValue
            }
        }
    }

    var protocolConfiguration: NEVPNProtocol? {
        get {
            lock.withCriticalBlock { tunnelInfo.protocolConfiguration }
        }
        set {
            lock.withCriticalBlock {
                tunnelInfo.protocolConfiguration = newValue
            }
        }
    }

    var localizedDescription: String? {
        get {
            lock.withCriticalBlock { tunnelInfo.localizedDescription }
        }
        set {
            lock.withCriticalBlock {
                tunnelInfo.localizedDescription = newValue
            }
        }
    }

    var connection: SimulatorVPNConnection {
        lock.withCriticalBlock { tunnelInfo.connection }
    }

    static func loadAllFromPreferences(completionHandler: ([SimulatorTunnelProviderManager]?, Error?) -> Void) {
        tunnelsLock.withCriticalBlock {
            completionHandler(tunnels.map { SimulatorTunnelProviderManager(tunnelInfo: $0) }, nil)
        }
    }

    required init() {
        self.tunnelInfo = SimulatorTunnelInfo()
    }

    private init(tunnelInfo: SimulatorTunnelInfo) {
        self.tunnelInfo = tunnelInfo
    }

    func loadFromPreferences(completionHandler: (Error?) -> Void) {
        Self.tunnelsLock.withCriticalBlock {
            if let savedTunnel = Self.tunnels.first(where: { $0.identifier == self.identifier }) {
                self.tunnelInfo = savedTunnel

                completionHandler(nil)
            } else {
                completionHandler(NEVPNError(.configurationInvalid))
            }

        }
    }

    func saveToPreferences(completionHandler: ((Error?) -> Void)?) {
        Self.tunnelsLock.withCriticalBlock {
            if let index = Self.tunnels.firstIndex(where: { $0.identifier == self.identifier }) {
                Self.tunnels[index] = self.tunnelInfo
            } else {
                Self.tunnels.append(self.tunnelInfo)
            }

            completionHandler?(nil)
        }
    }

    func removeFromPreferences(completionHandler: ((Error?) -> Void)?) {
        Self.tunnelsLock.withCriticalBlock {
            if let index = Self.tunnels.firstIndex(where: { $0.identifier == self.identifier }) {
                Self.tunnels.remove(at: index)
                completionHandler?(nil)
            } else {
                completionHandler?(NEVPNError(.configurationReadWriteFailed))
            }
        }
    }

    static func == (lhs: SimulatorTunnelProviderManager, rhs: SimulatorTunnelProviderManager) -> Bool {
        lhs.identifier == rhs.identifier
    }

}

#endif
