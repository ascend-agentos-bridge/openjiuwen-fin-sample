import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {marked} from 'marked';
// Markdown parsing uses a maintained library; DOMPurify sanitization is verified in browser QA.
test('rich Markdown emits semantic headings, table and list',()=>{
 const html=marked.parse('## 审查结果\n\n|额度|月供|\n|---|---|\n|800000|23548.08|\n\n- 待人工审批');
 assert.match(html,/<h2>/);assert.match(html,/<table>/);assert.match(html,/<li>/);
});
test('built entry uses actual renderer and chart packages without runtime CDN',()=>{
 const source=readFileSync(new URL('../src/main.js',import.meta.url),'utf8');
 assert.match(source,/@a2ui\/lit\/v0_9/);assert.match(source,/d3-org-chart/);assert.match(source,/DOMPurify.sanitize/);
 assert.doesNotMatch(source,/https:\/\/.*cdn/);
});
