/*
 * Copyright (C) 2014 Hari Krishna Dulipudi
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

package dev.dworks.apps.anexplorer;

import android.os.Bundle;

import dev.dworks.apps.anexplorer.common.ActionBarActivity;
import dev.dworks.apps.anexplorer.fragment.QuickShareFragment;

/** Hosts {@link QuickShareFragment} on its own, for the Quick Share root. */
public class QuickShareReceiveActivity extends ActionBarActivity {

    public static final String TAG = "QuickShareReceive";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quickshare_receive);

        if (null == savedInstanceState) {
            final QuickShareFragment fragment = new QuickShareFragment();
            final Bundle args = new Bundle();
            args.putBoolean(QuickShareFragment.EXTRA_STANDALONE, true);
            fragment.setArguments(args);
            getFragmentManager().beginTransaction()
                    .replace(R.id.container, fragment, QuickShareFragment.TAG)
                    .commit();
        }
    }

    @Override
    public String getTag() {
        return TAG;
    }
}
