import { Navigate, Route, Routes } from 'react-router-dom'
import LoginPage from './components/login/LoginPage'
import RegisterPage from './components/register/RegisterPage'
import ResetPasswordPage from './components/reset/ResetPasswordPage'
import HomePage from './pages/HomePage'
import BookingPage from './pages/BookingPage'
import BoardingDetailPage from './pages/BoardingDetailPage'
import BoardingPage from './pages/BoardingPage'
import ChatPage from './pages/ChatPage'
import MessagesPage from './pages/MessagesPage'
import OrderDetailPage from './pages/OrderDetailPage'
import OrdersPage from './pages/OrdersPage'
import PetDetailPage from './pages/PetDetailPage'
import PetFormPage from './pages/PetFormPage'
import PetsPage from './pages/PetsPage'
import ProfilePage from './pages/ProfilePage'

/**
 * 路由。登录/注册分离（ADR-009）。/home 为 C 端主页：门店区按第二段
 * GET /public/stores 契约取数，接口未上线时回退本地演示数据。
 * /booking 预约下单（ADR-016）、/pets/** 宠物档案（ADR-014）、/profile 我的。
 * /messages 消息中心（ADR-041，退款三类通知的只读收件箱，点击跳 /orders/{appointmentId}）。
 * 未匹配路由回落到 /login。
 */
export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route path="/reset-password" element={<ResetPasswordPage />} />
      <Route path="/home" element={<HomePage />} />
      <Route path="/booking" element={<BookingPage />} />
      <Route path="/boarding" element={<BoardingPage />} />
      <Route path="/boarding/:id" element={<BoardingDetailPage />} />
      <Route path="/orders" element={<OrdersPage />} />
      <Route path="/orders/:id" element={<OrderDetailPage />} />
      <Route path="/messages" element={<MessagesPage />} />
      {/* 门店沟通聊天页（P3）：独立全屏布局（无底部主导航），刷新可经详情接口恢复 */}
      <Route path="/chat/:conversationId" element={<ChatPage />} />
      <Route path="/profile" element={<ProfilePage />} />
      <Route path="/pets" element={<PetsPage />} />
      <Route path="/pets/new" element={<PetFormPage mode="create" />} />
      <Route path="/pets/:id" element={<PetDetailPage />} />
      <Route path="/pets/:id/edit" element={<PetFormPage mode="edit" />} />
      <Route path="*" element={<Navigate to="/login" replace />} />
    </Routes>
  )
}
