import { useEffect, useMemo, useState } from 'react';
import { Modal, Pressable, ScrollView, StyleSheet, Text, TextInput, View, useWindowDimensions } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';

import { useAuth } from '@/context/AuthContext';
import AccountMenu from '@/components/AccountMenu';
import { requestJson } from '@/api/client';
import {
  filterAndSortOrders, ORDER_STATUSES, orderTotal,
  type OrderFilter, type RestaurantOrder, type RestaurantOrderStatus,
} from '@/services/restaurant/orderQueue';

const label = (status: RestaurantOrderStatus) => status.replace('_', ' ').replace(/^./, (letter) => letter.toUpperCase());
const dateTime = (value: string) => new Date(value).toLocaleString('en-CA', { dateStyle: 'medium', timeStyle: 'short' });
const money = (value: number) => `$${value.toFixed(2)}`;
const LIFECYCLE = ORDER_STATUSES.slice(0, 6);

export default function RestaurantOrders() {
  const router = useRouter();
  const { width } = useWindowDimensions();
  const { user, token } = useAuth();
  const restaurantId = user?.restaurantId;
  const [orders, setOrders] = useState<RestaurantOrder[]>([]);
  const [filter, setFilter] = useState<OrderFilter>('placed');
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [detailId, setDetailId] = useState<number | null>(null);
  const [rejecting, setRejecting] = useState(false);
  const [rejectionReason, setRejectionReason] = useState('');
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [message, setMessage] = useState('');
  const [toast, setToast] = useState('');
  const columns = width >= 1050 ? 2 : 1;

  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(''), 4200);
    return () => clearTimeout(timer);
  }, [toast]);

  useEffect(() => {
    if (!token || user?.role !== 'restaurant') router.replace('/auth/login');
  }, [token, user?.role, router]);

  useEffect(() => {
    if (!restaurantId || !token) return;
    let active = true;
    // Refresh from the server so incoming and terminal orders reflect database state.
    const refresh = async () => {
      try {
        const data = await requestJson<{ id: number; status: string; dateOrdered: string; acceptedAt: string | null; rejectionReason: string | null; customerName: string | null; customerPhone: string | null; orderLines: { itemName: string; quantity: number; unitPrice: number }[] }[]>('GET', `/api/restaurants/${restaurantId}/orders`, undefined, token);
        if (!active) return;
        console.info('Restaurant orders loaded', { restaurantId, count: data.length });
        setOrders(data.map((order) => ({ id: order.id, restaurantId, status: order.status.toLowerCase() as RestaurantOrderStatus, createdAt: order.dateOrdered, updatedAt: null, acceptedAt: order.acceptedAt, rejectionReason: order.rejectionReason, customer: { name: order.customerName ?? 'Unavailable', address: 'Unavailable', phone: order.customerPhone ?? 'Unavailable' }, lines: order.orderLines.map((line) => ({ name: line.itemName, quantity: line.quantity, unitPrice: line.unitPrice, toppings: [] })) })));
        setMessage('');
      } catch (error) { if (active) { console.error('Unable to load restaurant orders', error); setMessage(error instanceof Error ? error.message : 'Unable to load orders'); } }
    };
    void refresh();
    const timer = setInterval(refresh, 10000);
    return () => { active = false; clearInterval(timer); };
  }, [restaurantId, token]);

  const shown = useMemo(() => filterAndSortOrders(orders, filter), [orders, filter]);
  const detail = orders.find((order) => order.id === detailId) ?? null;
  const allShownSelected = shown.length > 0 && shown.every((order) => selected.has(order.id));

  const toggleSelected = (id: number) => setSelected((current) => {
    const next = new Set(current);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });

  const changeStatus = async (nextStatus: RestaurantOrderStatus, reason?: string, target: RestaurantOrder | null = detail) => {
    if (!target || !restaurantId || !token) return;
    try {
      // Update the database first; never report a local-only status change as successful.
      const actions: Partial<Record<RestaurantOrderStatus, string>> = { accepted: 'accept', rejected: 'reject', preparing: 'prepare', ready: 'ready', picked_up: 'picked-up', delivered: 'delivered' };
      const action = actions[nextStatus];
      if (!action) throw new Error('This status change is not available.');
      const updated = await requestJson<{ status: string; acceptedAt: string | null; rejectionReason: string | null }>('POST', `/api/restaurants/${restaurantId}/orders/${target.id}/${action}`, nextStatus === 'rejected' ? { reason: reason?.trim() || null } : undefined, token);
      console.info('Restaurant order status updated', { orderId: target.id, status: nextStatus });
      setOrders((current) => current.map((order) => order.id === target.id ? { ...order, status: updated.status.toLowerCase() as RestaurantOrderStatus, acceptedAt: updated.acceptedAt, rejectionReason: updated.rejectionReason } : order));
      if (nextStatus === 'rejected') { setToast(`Order #${target.id} was rejected.`); setDetailId(null); }
      setRejecting(false);
      setRejectionReason('');
    } catch (error) {
      console.error('Restaurant order status update failed', error);
      setMessage(error instanceof Error ? error.message : 'Could not update the order.');
    }
  };

  const removeSelected = () => {
    // The API does not yet expose deletion; do not remove database orders from the display only.
    setMessage('Deleting orders is not available on the server yet.');
    setConfirmDelete(false);
  };

  if (!user || user.role !== 'restaurant') return null;

  return <SafeAreaView style={styles.page}>
    <ScrollView contentContainerStyle={styles.content}>
      <View style={styles.header}><Pressable accessibilityRole="button" onPress={() => router.replace('/restaurant')}><Text style={styles.back}>‹  Restaurant dashboard</Text></Pressable><AccountMenu /></View>
      <Text style={styles.restaurantName}>{user.name}</Text>
      <Text style={styles.title}>Orders queue</Text>
      {!!toast && <View style={styles.toast}><Text style={styles.toastText}>{toast}</Text><Pressable accessibilityRole="button" accessibilityLabel="Dismiss notification" onPress={() => setToast('')}><Text style={styles.toastClose}>×</Text></Pressable></View>}

      {!restaurantId ? <View style={styles.empty}>
        <Text style={styles.emptyTitle}>Restaurant unavailable</Text>
        <Text style={styles.emptyText}>Sign in with a restaurant account to view its orders.</Text>
      </View> : <>
        {width < 1050 && <Pressable accessibilityRole="button" accessibilityLabel="Choose order queue" accessibilityState={{ expanded: filtersOpen }} onPress={() => setFiltersOpen(!filtersOpen)} style={styles.filterToggle}><Text style={styles.filterToggleText}>Queue: {filter === 'all' ? 'All' : label(filter)} ({shown.length})</Text><Text style={styles.filterToggleText}>{filtersOpen ? '▴' : '▾'}</Text></Pressable>}
        {(width >= 1050 || filtersOpen) && <ScrollView horizontal={width >= 1050} showsHorizontalScrollIndicator={false} style={styles.filters} contentContainerStyle={width >= 1050 ? styles.filterContent : styles.filterList}>
          {(['all', ...ORDER_STATUSES] as OrderFilter[]).map((status) => {
            const count = status === 'all' ? orders.length : orders.filter((order) => order.status === status).length;
            return <Pressable key={status} accessibilityRole="button" accessibilityState={{ selected: filter === status }} style={[styles.filter, filter === status && styles.filterActive]} onPress={() => { setFilter(status); setFiltersOpen(false); }}>
              <Text style={[styles.filterText, filter === status && styles.filterTextActive]}>{status === 'all' ? 'All' : label(status)}  {count}</Text>
            </Pressable>;
          })}
        </ScrollView>}

        <View style={styles.queueToolbar}>
          <Text style={styles.queueCount}>{shown.length} {shown.length === 1 ? 'order' : 'orders'} · oldest first</Text>
          {orders.length > 0 && <Pressable accessibilityRole="checkbox" accessibilityState={{ checked: selected.size === orders.length }} onPress={() => setSelected(selected.size === orders.length ? new Set() : new Set(orders.map((order) => order.id)))}>
            <Text style={styles.toolbarLink}>{selected.size === orders.length ? 'Clear selection' : 'Select all'}</Text>
          </Pressable>}
          {shown.length > 0 && <Pressable accessibilityRole="button" onPress={() => setSelected((current) => {
            const next = new Set(current);
            for (const order of shown) { if (allShownSelected) next.delete(order.id); else next.add(order.id); }
            return next;
          })}>
            <Text style={styles.toolbarLink}>{allShownSelected ? 'Clear this queue' : 'Select this queue'}</Text>
          </Pressable>}
        </View>
        {selected.size > 0 && <Pressable accessibilityRole="button" accessibilityLabel="Delete selected orders" style={styles.deleteButton} onPress={() => setConfirmDelete(true)}><Text style={styles.deleteIcon}>🗑</Text><Text style={styles.deleteButtonText}>Delete selected ({selected.size})</Text></Pressable>}
        {!!message && <Text style={styles.message}>{message}</Text>}

        {shown.length === 0 ? <View style={styles.empty}>
          <Text style={styles.emptyTitle}>{filter === 'all' ? 'No orders recorded' : 'No orders in this status'}</Text>
          {filter !== 'all' && <Text style={styles.emptyText}>Choose another status to view the rest of the queue.</Text>}
        </View> : <View style={styles.orderGrid}>{shown.map((order) => <View key={order.id} style={[styles.orderRow, { width: columns === 1 ? '100%' : '49%' }]}>
          <Pressable accessibilityRole="checkbox" accessibilityState={{ checked: selected.has(order.id) }} accessibilityLabel={`Select order ${order.id} for deletion`} onPress={() => toggleSelected(order.id)} style={styles.checkArea}>
            <View style={[styles.checkbox, selected.has(order.id) && styles.checkboxChecked]}>{selected.has(order.id) && <Text style={styles.checkmark}>✓</Text>}</View>
          </Pressable>
          <View style={[styles.ticket, (order.status === 'rejected' || order.status === 'cancelled' || order.status === 'refunded' || order.status === 'delivered') && styles.orderTerminal]}>
          <Pressable accessibilityRole="button" accessibilityLabel={`View order ${order.id} details`} style={styles.orderBody} onPress={() => setDetailId(order.id)}>
            <View style={styles.cardContent}><View style={styles.cardTop}><Text style={styles.cardOrderId}>Order #{order.id}</Text><Text style={styles.cardTime}>{new Date(order.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</Text></View>
            <ScrollView horizontal showsHorizontalScrollIndicator={false}><View style={styles.statusRail}>{LIFECYCLE.map((status, index) => { const currentIndex = LIFECYCLE.indexOf(order.status as typeof LIFECYCLE[number]); const complete = currentIndex >= index; return <View key={status} style={styles.statusStep}><View style={[styles.statusDot, complete && styles.statusDotComplete]}><Text style={[styles.statusDotText, complete && styles.statusDotTextComplete]}>{complete ? '✓' : ''}</Text></View>{index < LIFECYCLE.length - 1 && <View style={[styles.statusConnector, currentIndex > index && styles.statusConnectorComplete]} />}<Text numberOfLines={1} style={[styles.statusStepLabel, complete && styles.statusStepLabelComplete]}>{label(status)}</Text></View>; })}</View></ScrollView>
            <View style={styles.orderLines}>{order.lines.map((line, index) => <View key={`${order.id}-${index}`} style={index > 0 ? styles.additionalLine : undefined}>
              <Text style={[styles.itemName, styles.cardItemText]}>{line.quantity} × {line.name}</Text>
              {line.toppings.length > 0 && <Text style={[styles.toppings, styles.cardMutedText]}>+ {line.toppings.map((topping) => topping.name).join(' · ')}</Text>}
            </View>)}</View>
            <View style={styles.rowEnd}><StatusBadge status={order.status} /><Text style={[styles.openHint, styles.cardMutedText]}>Tap for receipt details  ›</Text></View>
            </View>
          </Pressable>
          {order.status === 'placed' && <View style={styles.inlineActions}><Pressable accessibilityRole="button" style={styles.acceptButton} onPress={() => changeStatus('accepted', undefined, order)}><Text style={styles.actionText}>Accept</Text></Pressable><Pressable accessibilityRole="button" style={styles.rejectButton} onPress={() => { setDetailId(order.id); setRejecting(true); }}><Text style={styles.rejectText}>Reject</Text></Pressable></View>}
          {order.status === 'accepted' && <Pressable accessibilityRole="button" style={styles.acceptButton} onPress={() => changeStatus('preparing', undefined, order)}><Text style={styles.actionText}>Start preparing</Text></Pressable>}
          {order.status === 'preparing' && <Pressable accessibilityRole="button" style={styles.acceptButton} onPress={() => changeStatus('ready', undefined, order)}><Text style={styles.actionText}>Mark ready</Text></Pressable>}
          {order.status === 'ready' && <Pressable accessibilityRole="button" style={styles.acceptButton} onPress={() => changeStatus('picked_up', undefined, order)}><Text style={styles.actionText}>Mark picked up</Text></Pressable>}
          {order.status === 'picked_up' && <Pressable accessibilityRole="button" style={styles.acceptButton} onPress={() => changeStatus('delivered', undefined, order)}><Text style={styles.actionText}>Mark delivered</Text></Pressable>}
          </View>
        </View>)}</View>}
      </>}
    </ScrollView>

    <Modal transparent visible={detail !== null && !rejecting} animationType="fade" onRequestClose={() => setDetailId(null)}>
      <View style={styles.modalBackdrop}><View style={styles.modalCard}>
        {detail && <ScrollView contentContainerStyle={styles.modalContent}>
          <View style={styles.modalHeading}><View><Text style={styles.modalEyebrow}>ORDER RECORD</Text><Text style={styles.modalTitle}>Order #{detail.id}</Text></View><Pressable accessibilityRole="button" accessibilityLabel="Close order details" onPress={() => setDetailId(null)}><Text style={styles.close}>×</Text></Pressable></View>
          <StatusBadge status={detail.status} />
          <View style={styles.divider} />
          <Info label="Placed" value={dateTime(detail.createdAt)} />
          {detail.acceptedAt && <Info label="Accepted" value={dateTime(detail.acceptedAt)} />}
          <Info label="Customer" value={detail.customer.name} />
          <Info label="Delivery address" value={detail.customer.address} />
          <Info label="Contact" value={detail.customer.phone} />
          {detail.rejectionReason && <Info label="Rejection reason" value={detail.rejectionReason} />}
          <View style={styles.divider} />
          <Text style={styles.sectionTitle}>Items</Text>
          {detail.lines.map((line, index) => <View key={`${detail.id}-detail-${index}`} style={styles.detailLine}>
            <View style={styles.detailLineHeader}><Text style={styles.detailItemName}>{line.quantity} × {line.name}</Text><Text style={styles.detailPrice}>{money(line.quantity * (line.unitPrice + line.toppings.reduce((total, topping) => total + topping.additionalCost, 0)))}</Text></View>
            {line.toppings.map((topping, toppingIndex) => <Text key={toppingIndex} style={styles.detailTopping}>+ {topping.name} · {money(topping.additionalCost)} each</Text>)}
          </View>)}
          <View style={styles.totalRow}><Text style={styles.totalLabel}>Order total</Text><Text style={styles.totalValue}>{money(orderTotal(detail))}</Text></View>
          {detail.status === 'placed' && <View style={styles.actions}>
            <Pressable accessibilityRole="button" style={styles.primaryAction} onPress={() => changeStatus('accepted')}><Text style={styles.primaryActionText}>Accept order</Text></Pressable>
            <Pressable accessibilityRole="button" style={styles.secondaryAction} onPress={() => setRejecting(true)}><Text style={styles.secondaryActionText}>Reject order</Text></Pressable>
          </View>}
          {detail.status === 'accepted' && <Pressable accessibilityRole="button" style={styles.primaryAction} onPress={() => changeStatus('preparing')}><Text style={styles.primaryActionText}>Start preparing</Text></Pressable>}
          {detail.status === 'preparing' && <Pressable accessibilityRole="button" style={styles.primaryAction} onPress={() => changeStatus('ready')}><Text style={styles.primaryActionText}>Mark ready for pickup</Text></Pressable>}
        </ScrollView>}
      </View></View>
    </Modal>

    <Modal transparent visible={rejecting} animationType="fade" onRequestClose={() => setRejecting(false)}>
      <View style={styles.modalBackdrop}><View style={styles.dialog}>
        <Text style={styles.modalEyebrow}>ORDER DECISION</Text><Text style={styles.modalTitle}>Reject order</Text>
        <Text style={styles.dialogText}>Give the customer a clear reason for the rejection.</Text>
        <TextInput accessibilityLabel="Rejection reason (optional)" multiline value={rejectionReason} onChangeText={setRejectionReason} placeholder="Add a note for the customer (optional)" style={styles.reasonInput} />
        <View style={styles.dialogActions}><Pressable accessibilityRole="button" onPress={() => { setRejecting(false); setRejectionReason(''); }}><Text style={styles.toolbarLink}>Cancel</Text></Pressable><Pressable accessibilityRole="button" style={styles.dangerAction} onPress={() => changeStatus('rejected', rejectionReason)}><Text style={styles.primaryActionText}>Reject order</Text></Pressable></View>
      </View></View>
    </Modal>

    <Modal transparent visible={confirmDelete} animationType="fade" onRequestClose={() => setConfirmDelete(false)}>
      <View style={styles.modalBackdrop}><View style={styles.dialog}>
        <Text style={styles.modalEyebrow}>QUEUE CLEANUP</Text><Text style={styles.modalTitle}>Delete {selected.size} {selected.size === 1 ? 'order' : 'orders'}?</Text>
        <Text style={styles.dialogText}>Order deletion is not yet available on the server.</Text>
        <View style={styles.dialogActions}><Pressable accessibilityRole="button" onPress={() => setConfirmDelete(false)}><Text style={styles.toolbarLink}>Cancel</Text></Pressable><Pressable accessibilityRole="button" style={styles.dangerAction} onPress={removeSelected}><Text style={styles.primaryActionText}>Delete selected</Text></Pressable></View>
      </View></View>
    </Modal>
  </SafeAreaView>;
}

function StatusBadge({ status }: { status: RestaurantOrderStatus }) {
  return <View style={[styles.badge, status === 'placed' && styles.badgePlaced, status === 'ready' && styles.badgeReady]}><Text style={styles.badgeText}>{label(status)}</Text></View>;
}

function Info({ label: field, value }: { label: string; value: string }) {
  return <View style={styles.infoRow}><Text style={styles.infoLabel}>{field}</Text><Text style={[styles.infoValue, field === 'Customer' && styles.customerName]}>{value}</Text></View>;
}

const styles = StyleSheet.create({
  page: { flex: 1, backgroundColor: '#F7F7F5' },
  content: { width: '100%', maxWidth: 1180, alignSelf: 'center', paddingHorizontal: 22, paddingTop: 24, paddingBottom: 80 },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 28 },
  back: { color: '#5A626B', fontSize: 13, fontWeight: '700' },
  restaurantName: { color: '#526574', fontFamily: 'LoveLight', fontSize: 28, fontWeight: '400' },
  eyebrow: { color: '#526574', fontSize: 11, letterSpacing: 2, fontWeight: '800' },
  title: { color: '#242930', fontSize: 36, fontWeight: '800', marginTop: 10 },
  subtitle: { color: '#69717A', fontSize: 14, marginTop: 8, marginBottom: 24 },
  filters: { flexGrow: 0, marginBottom: 24 },
  filterContent: { gap: 8, paddingRight: 20 },
  filterList: { gap: 6, paddingBottom: 4 },
  filterToggle: { flexDirection: 'row', justifyContent: 'space-between', padding: 14, marginTop: 18, marginBottom: 8, borderWidth: 1, borderColor: '#D9DEE2', borderRadius: 7, backgroundColor: '#FFF' },
  filterToggleText: { color: '#28313A', fontSize: 14, fontWeight: '700' },
  filter: { paddingHorizontal: 14, paddingVertical: 10, borderWidth: 1, borderColor: '#D9DEE2', borderRadius: 7, backgroundColor: '#FFF' },
  filterActive: { backgroundColor: '#28313A', borderColor: '#28313A' },
  filterText: { color: '#48525C', fontSize: 12, fontWeight: '700' },
  filterTextActive: { color: '#FFF' },
  queueToolbar: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: 12, marginBottom: 12 },
  feedNotice: { color: '#69717A', fontSize: 12, marginBottom: 12 },
  queueCount: { color: '#66707A', fontSize: 12, fontWeight: '700' },
  toast: { backgroundColor: '#EAF6EE', borderColor: '#B8DDC3', borderWidth: 1, borderRadius: 9, paddingHorizontal: 14, paddingVertical: 11, marginTop: 15, marginBottom: 12, flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  toastText: { color: '#28633A', fontSize: 13, fontWeight: '700' },
  toastClose: { color: '#28633A', fontSize: 22, lineHeight: 24, paddingHorizontal: 5 },
  toolbarLink: { color: '#405766', fontSize: 12, fontWeight: '800' },
  deleteButton: { alignSelf: 'flex-end', flexDirection: 'row', alignItems: 'center', backgroundColor: '#A83B4B', paddingHorizontal: 16, paddingVertical: 10, borderRadius: 7, marginBottom: 14 },
  deleteButtonText: { color: '#FFF', fontWeight: '800', fontSize: 12 },
  deleteIcon: { color: '#FFF', fontSize: 15, fontWeight: '900', marginRight: 7 },
  message: { color: '#A83B4B', marginBottom: 12, fontSize: 12 },
  orderGrid: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'stretch', justifyContent: 'space-between', rowGap: 14 },
  orderRow: { flexDirection: 'row', minHeight: 184 },
  ticket: { flex: 1, backgroundColor: '#FFF', borderWidth: 1, borderColor: '#DDE2E3', borderRadius: 8, overflow: 'hidden' },
  orderTerminal: { backgroundColor: '#F4F5F5', borderColor: '#C9CDCE' },
  cardItemText: { color: '#333B40' },
  cardMutedText: { color: '#858C90' },
  checkArea: { width: 32, alignItems: 'flex-start', paddingTop: 18 },
  checkbox: { width: 18, height: 18, borderWidth: 1.5, borderColor: '#AAB2B8', backgroundColor: '#EAEDF0', borderRadius: 3, alignItems: 'center', justifyContent: 'center' },
  checkboxChecked: { backgroundColor: '#A83B4B', borderColor: '#A83B4B' },
  checkmark: { color: '#FFF', fontSize: 13, fontWeight: '900', lineHeight: 16 },
  orderBody: { flex: 1, paddingHorizontal: 14, paddingVertical: 14, flexDirection: 'column', alignItems: 'stretch', justifyContent: 'flex-start', gap: 12 },
  cardContent: { flex: 1 },
  cardTop: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 },
  cardOrderId: { color: '#273039', fontSize: 17, fontWeight: '800' },
  cardTime: { color: '#7A858D', fontSize: 11, fontWeight: '700' },
  statusRail: { flexDirection: 'row', alignItems: 'flex-start', marginBottom: 13, minWidth: 330, width: '100%' },
  statusStep: { flex: 1, alignItems: 'center', position: 'relative' },
  statusDot: { width: 17, height: 17, borderRadius: 10, borderWidth: 1.5, borderColor: '#C8CDD0', backgroundColor: '#F3F4F4', alignItems: 'center', justifyContent: 'center', zIndex: 1 },
  statusDotComplete: { borderColor: '#3A9B59', backgroundColor: '#3A9B59' },
  statusDotText: { color: 'transparent', fontSize: 10, fontWeight: '900', lineHeight: 12 },
  statusDotTextComplete: { color: '#FFF' },
  statusConnector: { position: 'absolute', height: 2, backgroundColor: '#DDE1E3', left: '58%', right: '-42%', top: 7 },
  statusConnectorComplete: { backgroundColor: '#70B785' },
  statusStepLabel: { color: '#92999E', fontSize: 8, marginTop: 5, textAlign: 'center' },
  statusStepLabelComplete: { color: '#3B734B', fontWeight: '700' },
  inlineActions: { alignItems: 'flex-end', paddingHorizontal: 14, paddingBottom: 14, gap: 7 },
  acceptButton: { backgroundColor: '#2E8B49', paddingHorizontal: 12, paddingVertical: 10, borderRadius: 6, alignItems: 'center', alignSelf: 'flex-end', minWidth: 105, marginHorizontal: 14, marginBottom: 10 },
  rejectButton: { backgroundColor: '#B63E48', paddingHorizontal: 12, paddingVertical: 10, borderRadius: 6, alignItems: 'center', width: 105, marginRight: 14 },
  actionText: { color: '#FFF', fontWeight: '800', fontSize: 12 },
  rejectText: { color: '#FFF', fontWeight: '800', fontSize: 12 },
  orderLines: { flex: 1 },
  additionalLine: { marginTop: 8 },
  itemName: { color: '#273039', fontSize: 15, fontWeight: '800' },
  toppings: { color: '#68737C', fontSize: 12, marginTop: 3 },
  rowEnd: { alignItems: 'flex-end', gap: 9 },
  openHint: { color: '#8B949B', fontSize: 11 },
  badge: { alignSelf: 'flex-start', backgroundColor: '#EDF0F2', borderRadius: 5, paddingHorizontal: 9, paddingVertical: 5 },
  badgePlaced: { backgroundColor: '#FFF0D8' },
  badgeReady: { backgroundColor: '#DDF3E7' },
  badgeText: { color: '#35414B', fontSize: 11, fontWeight: '800' },
  empty: { backgroundColor: '#FFF', borderWidth: 1, borderColor: '#E1E4E6', borderRadius: 10, padding: 30, marginTop: 10 },
  emptyTitle: { color: '#273039', fontSize: 18, fontWeight: '800' },
  emptyText: { color: '#68737C', fontSize: 13, lineHeight: 20, marginTop: 7 },
  modalBackdrop: { flex: 1, backgroundColor: 'rgba(24,30,35,0.52)', justifyContent: 'center', alignItems: 'center', padding: 16 },
  modalCard: { width: '100%', maxWidth: 620, maxHeight: '92%', backgroundColor: '#FFF', borderRadius: 13 },
  modalContent: { padding: 26 },
  modalHeading: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 15 },
  modalEyebrow: { color: '#9F3444', letterSpacing: 1.6, fontSize: 10, fontWeight: '800' },
  modalTitle: { color: '#273039', fontSize: 25, fontWeight: '800', marginTop: 6 },
  close: { color: '#69737C', fontSize: 28, lineHeight: 30 },
  divider: { height: 1, backgroundColor: '#E8ECEE', marginVertical: 20 },
  infoRow: { flexDirection: 'row', justifyContent: 'space-between', gap: 20, paddingVertical: 6 },
  infoLabel: { width: 106, color: '#68737C', fontSize: 12, fontWeight: '700' },
  infoValue: { flex: 1, color: '#273039', fontSize: 13, textAlign: 'right' },
  customerName: { fontFamily: 'LoveLight', fontSize: 26, fontWeight: '400' },
  sectionTitle: { color: '#273039', fontSize: 16, fontWeight: '800', marginBottom: 6 },
  detailLine: { paddingVertical: 10, borderBottomWidth: 1, borderBottomColor: '#EEF0F1' },
  detailLineHeader: { flexDirection: 'row', justifyContent: 'space-between', gap: 12 },
  detailItemName: { color: '#273039', fontSize: 14, fontWeight: '700', flex: 1 },
  detailPrice: { color: '#273039', fontSize: 14, fontWeight: '800' },
  detailTopping: { color: '#68737C', fontSize: 12, marginTop: 4, marginLeft: 17 },
  totalRow: { flexDirection: 'row', justifyContent: 'space-between', marginTop: 17, marginBottom: 24 },
  totalLabel: { color: '#273039', fontSize: 15, fontWeight: '800' },
  totalValue: { color: '#273039', fontSize: 17, fontWeight: '800' },
  actions: { flexDirection: 'row', gap: 10 },
  primaryAction: { backgroundColor: '#26313A', paddingHorizontal: 17, paddingVertical: 13, borderRadius: 7, alignItems: 'center', marginTop: 10, flexGrow: 1 },
  primaryActionText: { color: '#FFF', fontSize: 12, fontWeight: '800' },
  secondaryAction: { borderWidth: 1, borderColor: '#A83B4B', paddingHorizontal: 17, paddingVertical: 13, borderRadius: 7, alignItems: 'center', marginTop: 10, flexGrow: 1 },
  secondaryActionText: { color: '#A83B4B', fontSize: 12, fontWeight: '800' },
  dialog: { width: '100%', maxWidth: 480, backgroundColor: '#FFF', borderRadius: 13, padding: 26 },
  dialogText: { color: '#68737C', fontSize: 13, lineHeight: 20, marginTop: 14 },
  reasonInput: { borderWidth: 1, borderColor: '#D5DADD', borderRadius: 7, minHeight: 90, padding: 12, textAlignVertical: 'top', marginTop: 15, color: '#273039' },
  dialogActions: { flexDirection: 'row', alignItems: 'center', justifyContent: 'flex-end', gap: 22, marginTop: 23 },
  dangerAction: { backgroundColor: '#A83B4B', paddingHorizontal: 17, paddingVertical: 12, borderRadius: 7 },
  disabledAction: { opacity: 0.45 },
});
