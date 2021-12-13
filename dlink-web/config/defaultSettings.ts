import { Settings as LayoutSettings } from '@ant-design/pro-layout';

const Settings: LayoutSettings & {
  pwa?: boolean;
  logo?: string;
} = {
  navTheme: 'realDark',
  // 拂晓蓝
  primaryColor: '#1890ff',
  layout: 'mix',
  contentWidth: 'Fluid',
  fixedHeader: false,
  fixSiderbar: true,
  colorWeak: false,
  title: 'OneSQL',
  pwa: false,
  logo: '/default_logo.png',
  iconfontUrl: '',
  menu: {
    locale: true
  },
  headerHeight: 48,
  splitMenus: true
};

export default Settings;
