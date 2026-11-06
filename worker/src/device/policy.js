import { SENSITIVE_CAPABILITIES } from "./protocol.js";

/**
 * Capability authorization for enqueue and policy checks.
 *
 * Sensitive capabilities (e.g. screen.control) may be queued so the target
 * device can present a local approval UI. Remote `userConfirmed` is NEVER
 * treated as proof of user consent and must not unlock silent execution.
 * Actual execution is gated on the device by ScreenActionApprovalActivity.
 */
export function authorizeCapability(device, capability, request = {}) {
  if (!device?.capabilities?.includes(capability)) {
    return { allowed: false, reason: "Capability has not been granted on this device." };
  }

  if (SENSITIVE_CAPABILITIES.has(capability)) {
    // Intentionally ignore request.userConfirmed from remote clients.
    return {
      allowed: true,
      requiresConfirmation: true,
      onDeviceApprovalRequired: true,
      reason: "This action requires explicit user confirmation on the target device before execution."
    };
  }

  return { allowed: true };
}

export function publicDevice(d) {
  return {
    deviceId: d.deviceId,
    accountId: d.accountId,
    platform: d.platform,
    name: d.name,
    osVersion: d.osVersion,
    appVersion: d.appVersion,
    capabilities: d.capabilities,
    lastSeen: d.lastSeen,
    protocolVersion: d.protocolVersion
  };
}