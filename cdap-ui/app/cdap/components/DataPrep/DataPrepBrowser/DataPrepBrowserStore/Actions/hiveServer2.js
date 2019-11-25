/*
 * Copyright © 2018 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

import {setActiveBrowser, setError} from './commons';
import DataPrepBrowserStore, {Actions as BrowserStoreActions} from 'components/DataPrep/DataPrepBrowser/DataPrepBrowserStore';
import NamespaceStore from 'services/NamespaceStore';
import MyDataPrepApi from 'api/dataprep';
import {objectQuery} from 'services/helpers';

const setHiveServer2InfoLoading = () => {
  DataPrepBrowserStore.dispatch({
    type: BrowserStoreActions.SET_HIVESERVER2_LOADING,
    payload: {
      loading: true
    }
  });
};

const setHiveServer2AsActiveBrowser = (payload) => {
  let {hive, activeBrowser} = DataPrepBrowserStore.getState();

  if (activeBrowser.name !== payload.name) {
    setActiveBrowser(payload);
  }

  let {id: connectionId} = payload;

  if (hive.connectionId === connectionId) { return; }

  DataPrepBrowserStore.dispatch({
    type: BrowserStoreActions.SET_HIVE_CONNECTION_ID,
    payload: {
      connectionId
    }
  });

  setHiveServer2InfoLoading();

  let namespace = NamespaceStore.getState().selectedNamespace;

  let params = {
    namespace,
    connectionId
  };
  MyDataPrepApi.getConnection(params)
    .subscribe((res) => {
      let info = objectQuery(res, 'values', 0);

      MyDataPrepApi.listTables(params)
        .subscribe((tables) => {
          setHiveServer2Properties({
            info,
            tables: tables.values,
            connectionId: params.connectionId,
          });
        }, (err) => {
          setError(err);
        });
    }, (err) => {
      setError(err);
    });
};

const setHiveServer2Properties = (payload) => {
  DataPrepBrowserStore.dispatch({
    type: BrowserStoreActions.SET_HIVESERVER2_PROPERTIES,
    payload
  });
};

export {
  setHiveServer2InfoLoading,
  setHiveServer2AsActiveBrowser,
  setHiveServer2Properties
};
