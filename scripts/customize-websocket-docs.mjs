import { copyFile, readFile, writeFile } from 'node:fs/promises';

const siteDirectory = new URL('../build/generated/websocket-docs/html/', import.meta.url);
const sourceCss = new URL('../src/test/resources/websocket-docs/drawer.css', import.meta.url);
const sourceOperationLabels = new URL('../src/test/resources/websocket-docs/operation-sidebar-labels.js', import.meta.url);
const targetCss = new URL('./css/igmo-drawer.css', siteDirectory);
const targetOperationLabels = new URL('./js/igmo-operation-sidebar-labels.js', siteDirectory);
const indexHtml = new URL('./index.html', siteDirectory);
const stylesheet = '      <link rel="stylesheet" href="css/igmo-drawer.css">\n';
const operationLabels = '      <script type="application/javascript" src="js/igmo-operation-sidebar-labels.js"></script>\n';

await copyFile(sourceCss, targetCss);
await copyFile(sourceOperationLabels, targetOperationLabels);

const html = await readFile(indexHtml, 'utf8');
let customizedHtml = html;
if (!customizedHtml.includes('css/igmo-drawer.css')) {
    customizedHtml = customizedHtml.replace('    </head>', `${stylesheet}    </head>`);
}
if (!customizedHtml.includes('js/igmo-operation-sidebar-labels.js')) {
    customizedHtml = customizedHtml.replace('    </body>', `${operationLabels}    </body>`);
}
if (customizedHtml !== html) {
    await writeFile(indexHtml, customizedHtml);
}
