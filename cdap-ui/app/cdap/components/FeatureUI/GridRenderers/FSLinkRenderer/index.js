/*
 * Copyright © 2017 Cask Data, Inc.
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

/* eslint react/prop-types: 0 */
import React from 'react';

class FSLinkRenderer extends React.Component {
  constructor(props) {
    super(props);
  }

  invokeParentMethod(item) {
    if (this.props.context && this.props.context.componentParent && this.props.context.componentParent.onEdit) {
      this.props.context.componentParent.onFeatureSelection(item);
    }
  }
  render() {
    return  <div className="view-link" onClick={this.invokeParentMethod.bind(this, this.props.data)}>
       Feature Selection</div>;
  }
}
export default FSLinkRenderer;