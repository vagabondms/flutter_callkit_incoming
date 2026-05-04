import AVFoundation
import AVKit
import Flutter
import UIKit

@available(iOS 10.0, *)
final class CallAudioRouteManager: NSObject, FlutterStreamHandler {
    private let channelName = "flutter_callkit_incoming_call_audio_route_events"
    private var eventSink: FlutterEventSink?

    override init() {
        super.init()
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(audioRouteChanged),
            name: AVAudioSession.routeChangeNotification,
            object: nil
        )
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    func register(with registrar: FlutterPluginRegistrar) {
        FlutterEventChannel(
            name: channelName,
            binaryMessenger: registrar.messenger()
        ).setStreamHandler(self)
    }

    func getDevices(callId: String?) -> [[String: Any?]] {
        return buildDevices()
    }

    func getRoute(callId: String?) -> String {
        return routeName(for: AVAudioSession.sharedInstance().currentRoute.outputs.first)
    }

    func setRoute(callId: String?, deviceId: String) -> Bool {
        let session = AVAudioSession.sharedInstance()
        do {
            switch deviceId {
            case "builtin_speaker":
                try session.overrideOutputAudioPort(.speaker)
            case "builtin_receiver":
                try session.overrideOutputAudioPort(.none)
            default:
                return false
            }
            emitSnapshot(callId: callId)
            return true
        } catch {
            print("set call audio route failed: \(error)")
            return false
        }
    }

    func showRoutePicker(callId: String?) -> Bool {
        DispatchQueue.main.async {
            let routePickerView = AVRoutePickerView(frame: .zero)
            routePickerView.prioritizesVideoDevices = false

            guard
                let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
                let window = windowScene.windows.first
            else {
                return
            }

            window.addSubview(routePickerView)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                for subview in routePickerView.subviews {
                    if let button = subview as? UIButton {
                        button.sendActions(for: .touchUpInside)
                        break
                    }
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                    routePickerView.removeFromSuperview()
                }
            }
        }
        return true
    }

    func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        eventSink = events
        emitSnapshot(callId: nil)
        return nil
    }

    func onCancel(withArguments arguments: Any?) -> FlutterError? {
        eventSink = nil
        return nil
    }

    @objc private func audioRouteChanged(notification: Notification) {
        emitSnapshot(callId: nil)
    }

    private func emitSnapshot(callId: String?) {
        eventSink?([
            "callId": callId as Any,
            "route": getRoute(callId: callId),
            "devices": buildDevices(),
        ])
    }

    private func buildDevices() -> [[String: Any?]] {
        let session = AVAudioSession.sharedInstance()
        let outputs = session.currentRoute.outputs
        let route = getRoute(callId: nil)
        let selectedOutputIds = Set(outputs.map { "ios:\($0.uid)" })
        var devices: [[String: Any?]] = []
        var deviceIds = Set<String>()

        appendDevice(
            &devices,
            ids: &deviceIds,
            id: "builtin_receiver",
            name: "Phone",
            route: "receiver",
            selected: route == "receiver"
        )
        appendDevice(
            &devices,
            ids: &deviceIds,
            id: "builtin_speaker",
            name: "Speaker",
            route: "speaker",
            selected: route == "speaker"
        )

        for output in outputs where output.portType != .builtInReceiver && output.portType != .builtInSpeaker {
            let mappedRoute = routeName(for: output)
            appendDevice(
                &devices,
                ids: &deviceIds,
                id: "ios:\(output.uid)",
                name: output.portName,
                route: mappedRoute,
                selected: selectedOutputIds.contains("ios:\(output.uid)")
            )
        }

        for input in session.availableInputs ?? [] where isExternalInput(input) {
            appendDevice(
                &devices,
                ids: &deviceIds,
                id: "ios:\(input.uid)",
                name: input.portName,
                route: routeName(for: input),
                selected: selectedOutputIds.contains("ios:\(input.uid)")
            )
        }

        return devices
    }

    private func appendDevice(
        _ devices: inout [[String: Any?]],
        ids: inout Set<String>,
        id: String,
        name: String,
        route: String,
        selected: Bool
    ) {
        guard !ids.contains(id) else {
            return
        }
        ids.insert(id)
        devices.append(device(id: id, name: name, route: route, selected: selected))
    }

    private func device(id: String, name: String, route: String, selected: Bool) -> [String: Any?] {
        return [
            "id": id,
            "name": name,
            "route": route,
            "isSelected": selected,
        ]
    }

    private func routeName(for output: AVAudioSessionPortDescription?) -> String {
        guard let output = output else {
            return "unknown"
        }
        switch output.portType {
        case .builtInReceiver:
            return "receiver"
        case .builtInSpeaker:
            return "speaker"
        case .bluetoothA2DP, .bluetoothHFP, .bluetoothLE, .airPlay:
            return "bluetooth"
        case .headphones, .headsetMic, .usbAudio:
            return "wiredHeadset"
        default:
            return "unknown"
        }
    }

    private func isExternalInput(_ input: AVAudioSessionPortDescription) -> Bool {
        switch input.portType {
        case .bluetoothA2DP, .bluetoothHFP, .bluetoothLE, .headphones, .headsetMic, .usbAudio:
            return true
        default:
            return false
        }
    }
}
