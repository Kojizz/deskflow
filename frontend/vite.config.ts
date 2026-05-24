import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
export default defineConfig({plugins:[react()],server:{proxy:{'/api':'http://127.0.0.1:18080','/hooks':'http://127.0.0.1:18080','/forms':'http://127.0.0.1:18080','/swagger-ui':'http://127.0.0.1:18080','/v3/api-docs':'http://127.0.0.1:18080'}}});
