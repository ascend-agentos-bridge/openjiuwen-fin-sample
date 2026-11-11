import {defineConfig} from 'vite';
export default defineConfig({build:{outDir:'../java/src/main/resources/ui',emptyOutDir:true},server:{proxy:{'/chat':'http://127.0.0.1:8080','/config':'http://127.0.0.1:8080'}}});
