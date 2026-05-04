enum CallAudioRoute {
  receiver,
  speaker,
  bluetooth,
  wiredHeadset,
  unknown;

  static CallAudioRoute fromJson(final Object? value) {
    if (value is! String) {
      return CallAudioRoute.unknown;
    }
    return CallAudioRoute.values.firstWhere(
      (final route) => route.name == value,
      orElse: () => CallAudioRoute.unknown,
    );
  }
}

class CallAudioDevice {
  const CallAudioDevice({
    required this.id,
    required this.name,
    required this.route,
    required this.isSelected,
  });

  factory CallAudioDevice.fromJson(final Map<dynamic, dynamic> json) =>
      CallAudioDevice(
        id: json['id'] as String? ?? '',
        name: json['name'] as String? ?? '',
        route: CallAudioRoute.fromJson(json['route']),
        isSelected: json['isSelected'] as bool? ?? false,
      );

  final String id;
  final String name;
  final CallAudioRoute route;
  final bool isSelected;
}

class CallAudioRouteChangedEvent {
  const CallAudioRouteChangedEvent({
    required this.callId,
    required this.route,
    required this.devices,
  });

  factory CallAudioRouteChangedEvent.fromJson(
    final Map<dynamic, dynamic> json,
  ) {
    final devicesJson = json['devices'];
    return CallAudioRouteChangedEvent(
      callId: json['callId'] as String?,
      route: CallAudioRoute.fromJson(json['route']),
      devices: devicesJson is List
          ? devicesJson
                .whereType<Map<dynamic, dynamic>>()
                .map(CallAudioDevice.fromJson)
                .toList(growable: false)
          : const [],
    );
  }

  final String? callId;
  final CallAudioRoute route;
  final List<CallAudioDevice> devices;
}
