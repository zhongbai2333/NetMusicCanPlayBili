package com.zhongbai233.net_music_can_play_bili.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlConsolePermissionPolicyTest {
    @Test
    void opLevelTwoOrHigherCannotBeDeniedByPermissionNode() {
        assertTrue(ControlConsolePermissionPolicy.grantsAdministrator(false, true, false));
    }

    @Test
    void permissionNodeStillGrantsNonOperatorsAccess() {
        assertTrue(ControlConsolePermissionPolicy.grantsAdministrator(false, false, true));
    }

    @Test
    void singleplayerOwnerStillBypassesAccessControl() {
        assertTrue(ControlConsolePermissionPolicy.grantsAdministrator(true, false, false));
    }

    @Test
    void nonOperatorWithoutNodeRemainsDenied() {
        assertFalse(ControlConsolePermissionPolicy.grantsAdministrator(false, false, false));
    }

    @Test
    void externalDenialCannotOverrideVanillaOrOwnerFallbacks() {
        assertTrue(ControlConsolePermissionPolicy.grantsAdministrator(true, true, false));
    }
}
