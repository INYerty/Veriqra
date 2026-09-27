(function (global) {
  'use strict';
  global.VeriqraAccessLogFormat = Object.freeze({
    operatingSystem: function (value, translate) {
      return typeof value === 'string' && value.trim()
        ? value.trim() : translate('admin.unknown', null, 'Unknown');
    }
  });
})(window);
