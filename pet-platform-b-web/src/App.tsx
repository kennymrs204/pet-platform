import { Navigate, Route, Routes } from 'react-router-dom'
import LoginPage from './components/login/LoginPage'
import HomePage from './pages/HomePage'
import PetsPage from './pages/PetsPage'
import BoardingPage from './pages/BoardingPage'
import CustomerChatPage from './pages/CustomerChatPage'
import CustomersPage from './pages/CustomersPage'
import EmployeesPage from './pages/EmployeesPage'
import OrdersPage from './pages/OrdersPage'
import SettingsPage from './pages/SettingsPage'
import BathingPage from './pages/BathingPage'
import WalkingPage from './pages/WalkingPage'
import CarePage from './pages/CarePage'
import TasksPage from './pages/TasksPage'
import MessagesPage from './pages/MessagesPage'

/**
 * B 端路由。登录/注册在同一卡片内 Tab 切换（对齐设计稿），登录后进工作台首页。
 * 与 C 端（pet-platform-c-web，端口 5173）为独立应用：独立 tokenStore，互不共享会话。
 */
export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/home" element={<HomePage />} />
      <Route path="/pets" element={<PetsPage />} />
      <Route path="/boarding" element={<BoardingPage />} />
      <Route path="/customers" element={<CustomersPage />} />
      {/* 客户沟通工作台（P4）：门店会话列表 + 聊天区；?open={conversationId} 深链恢复 */}
      <Route path="/customer-chat" element={<CustomerChatPage />} />
      <Route path="/employees" element={<EmployeesPage />} />
      <Route path="/orders" element={<OrdersPage />} />
      <Route path="/settings" element={<SettingsPage />} />
      <Route path="/bathing" element={<BathingPage />} />
      {/* 遛狗服务（ADR-049）：全店遛狗履约看板；遛狗单不在洗护看板（ADR-038 刻意排除） */}
      <Route path="/walking" element={<WalkingPage />} />
      {/* 宠物照顾：护理员的托管日常护理工作台（零后端新增，复用 boarding 域端点） */}
      <Route path="/care" element={<CarePage />} />
      {/* 我的任务（ADR-048）：服务人员侧待办闭环。遛狗/医疗在洗澡看板里看不见，故单开一页 */}
      <Route path="/tasks" element={<TasksPage />} />
      {/* 消息中心：铃铛是入口，不进左侧导航（导航项按业务模块排，消息不是模块） */}
      <Route path="/messages" element={<MessagesPage />} />
      <Route path="*" element={<Navigate to="/login" replace />} />
    </Routes>
  )
}
