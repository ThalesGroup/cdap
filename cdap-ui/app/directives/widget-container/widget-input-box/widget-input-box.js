/*
 * Copyright © 2015 Cask Data, Inc.
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

angular.module(PKG.name + '.commons')
  .directive('myInputBoxWidget', function() {
    return {
      restrict: 'E',
      scope: {
        disabled: '=',
        model: '=ngModel',
        placeholder: '@'
      },
      templateUrl: 'widget-container/widget-input-box/widget-input-box.html',
      controller: function($scope) {
        $scope.showErrorMessage = false;


        var isValidValue = function(dirty) {
          var allowed = {
            ALLOWED_TAGS: [],
          };

          const clean = window['DOMPurify'].sanitize(dirty, allowed);
          return clean === dirty ? true : false;
        };

        $scope.getInputInfoMessage = function() {
          if($scope.tooltip && $scope.tooltip !== undefined) {
            return $scope.tooltip + '\n Cannot  contain any xml tags.';
          } else {
            return 'Cannot  contain any xml tags.';
          }
        };

        $scope.getErrorMessage = function() {
          return 'Invalid Input, see help.';
        };

        $scope.onValueChange = function() {
          if($scope.model !== undefined) {
            $scope.showErrorMessage = isValidValue($scope.model) ? false : true;
          }
          return 'Invalid Input, see help.';
        };

        $scope.$watch('model', function() {
          if($scope.model !== undefined && $scope.model.trim() !==  '') {
            $scope.showErrorMessage = isValidValue($scope.model) ? false : true;
          }
        });

      }
    };
  });