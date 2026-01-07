import { NgModule } from '@angular/core';
import { OpfOrderModule } from "@spartacus/opf/order";
import { OrderModule } from "@spartacus/order";

@NgModule({
  declarations: [],
  imports: [
    OrderModule,
    OpfOrderModule
  ]
})
export class OrderWrapperModule { }
