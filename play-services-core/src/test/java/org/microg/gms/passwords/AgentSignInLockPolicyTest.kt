/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class AgentSignInLockPolicyTest {
    @Test fun onlyTheFirstUnlockIsWaitedFor() {
        assertFalse(AgentSignInLockPolicy.storageOpen(false))
        assertTrue(AgentSignInLockPolicy.storageOpen(true))
    }
}
