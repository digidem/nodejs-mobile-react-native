import { registerWebModule, NativeModule } from 'expo';

import { RNNodeJsMobileModuleEvents } from './RNNodeJsMobile.types';

class RNNodeJsMobileModule extends NativeModule<RNNodeJsMobileModuleEvents> {
  PI = Math.PI;
  async setValueAsync(value: string): Promise<void> {
    this.emit('onChange', { value });
  }
  hello() {
    return 'Hello world! 👋';
  }
}

export default registerWebModule(RNNodeJsMobileModule, 'RNNodeJsMobileModule');
