const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const web = path.resolve(__dirname, '../../main/webapp');
const source = fs.readFileSync(path.join(web, 'admin/access-log-format.js'), 'utf8');
const catalogs = Object.fromEntries(['en', 'zh-CN'].map(locale =>
  [locale, JSON.parse(fs.readFileSync(path.join(web, 'i18n', locale + '.json'), 'utf8'))]));
const window = {};
vm.runInNewContext(source, { window });

test('access log OS column keeps parsed values and localizes unknown values', () => {
  for (const locale of ['en', 'zh-CN']) {
    const translate = key => catalogs[locale][key];
    const format = value => window.VeriqraAccessLogFormat.operatingSystem(value, translate);
    assert.equal(format(' Windows '), 'Windows');
    assert.equal(format('iOS'), 'iOS');
    for (const missing of [null, undefined, '', '   ']) assert.equal(format(missing), catalogs[locale]['admin.unknown']);
  }
});
