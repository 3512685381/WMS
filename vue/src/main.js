import { createPinia } from 'pinia'
import { createApp } from 'vue'
import { ElButton, ElForm, ElFormItem, ElInput, ElLoading } from 'element-plus'
import 'element-plus/es/components/base/style/css'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/form/style/css'
import 'element-plus/es/components/form-item/style/css'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/loading/style/css'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import App from './App.vue'
import { installRouter } from './router'
import './styles/base.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
for (const plugin of [ElButton, ElForm, ElFormItem, ElInput, ElLoading]) {
  app.use(plugin)
}
installRouter(app, pinia)
app.mount('#app')
