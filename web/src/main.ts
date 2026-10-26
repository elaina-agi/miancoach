import './styles/tokens.css'
import './styles/base.css'

import { createApp } from 'vue'
import { initTheme } from './lib/theme'
import { initAuth } from './lib/auth'
import App from './App.vue'
import router from './router'

initTheme()
// 不 await:页面先渲染,token 换用户信息在后台进行(期间顶栏账号区留空,见 AppShell)
void initAuth()

createApp(App).use(router).mount('#app')
