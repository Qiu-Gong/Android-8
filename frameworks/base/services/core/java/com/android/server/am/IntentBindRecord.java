/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.am;

import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.ArrayMap;
import android.util.ArraySet;

import java.io.PrintWriter;

/**
 * A particular Intent that has been bound to a Service.
 */
final class IntentBindRecord {
    /** The running service. */
	// 被绑定的 Service 
    final ServiceRecord service;
    /** The intent that is bound.*/
	// 绑定 Service 的 Intent
    final Intent.FilterComparison intent; // 
    /** All apps that have bound to this Intent. */
	// 所有用当前 Intent 绑定 Service 的应用程序进程
	// 不同的应用程序进程可能使用同一个 Intent 来绑定 Service
    final ArrayMap<ProcessRecord, AppBindRecord> apps
            = new ArrayMap<ProcessRecord, AppBindRecord>();
    /** Binder published from service. */
    IBinder binder;
    /** Set when we have initiated a request for this binder. */
    boolean requested;
    /** Set when we have received the requested binder. */
    boolean received;
    /** Set when we still need to tell the service all clients are unbound. */
    boolean hasBound;
    /** Set when the service's onUnbind() has asked to be told about new clients. */
    boolean doRebind;
    
    String stringName;      // caching of toString
    
    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("service="); pw.println(service);
        dumpInService(pw, prefix);
    }

    void dumpInService(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("intent={");
                pw.print(intent.getIntent().toShortString(false, true, false, false));
                pw.println('}');
        pw.print(prefix); pw.print("binder="); pw.println(binder);
        pw.print(prefix); pw.print("requested="); pw.print(requested);
                pw.print(" received="); pw.print(received);
                pw.print(" hasBound="); pw.print(hasBound);
                pw.print(" doRebind="); pw.println(doRebind);
        for (int i=0; i<apps.size(); i++) {
            AppBindRecord a = apps.valueAt(i);
            pw.print(prefix); pw.print("* Client AppBindRecord{");
                    pw.print(Integer.toHexString(System.identityHashCode(a)));
                    pw.print(' '); pw.print(a.client); pw.println('}');
            a.dumpInIntentBind(pw, prefix + "  ");
        }
    }

    IntentBindRecord(ServiceRecord _service, Intent.FilterComparison _intent) {
        service = _service;
        intent = _intent;
    }

    int collectFlags() {
        int flags = 0;
        for (int i=apps.size()-1; i>=0; i--) {
            final ArraySet<ConnectionRecord> connections = apps.valueAt(i).connections;
            for (int j=connections.size()-1; j>=0; j--) {
                flags |= connections.valueAt(j).flags;
            }
        }
        return flags;
    }

    public String toString() {
        if (stringName != null) {
            return stringName;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("IntentBindRecord{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(' ');
        if ((collectFlags()&Context.BIND_AUTO_CREATE) != 0) {
            sb.append("CR ");
        }
        sb.append(service.shortName);
        sb.append(':');
        if (intent != null) {
            intent.getIntent().toShortString(sb, false, false, false, false);
        }
        sb.append('}');
        return stringName = sb.toString();
    }
}
