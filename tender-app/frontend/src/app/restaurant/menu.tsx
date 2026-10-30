import { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Image, Pressable, StyleSheet, Text, TextInput, useWindowDimensions, View } from 'react-native';
import { Redirect, useRouter } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { DetailPage } from '../customer/components/DetailPage';
import { styles as customerStyles } from '../customer/restaurant/restaurant.styles';
import { useAuth } from '@/context/AuthContext';
import { loadRestaurantMenu, saveRestaurantMenu, type MenuItem, type RestaurantMenu } from '@/api/restaurantMenu';
import { CoverImagePicker } from '@/components/restaurant/CoverImagePicker';
import { readSessionToken } from '@/context/sessionStorage';
import { readRestaurantCover, writeRestaurantCover } from '@/storage/restaurantCover';
import { readRestaurantItemPhoto, writeRestaurantItemPhoto } from '@/storage/restaurantItemPhoto';

const newItem = (): MenuItem => ({ id: null, name: '', description: '', category: '', price: 0, status: 'available', photoUrl: null, options: [] });
const emptyRestaurantMenu = (restaurantId: number, name: string): RestaurantMenu => ({ restaurantId, name, status: 'closed', address: '', deliveryFee: 0, coverUrl: null, menuName: 'Menu', items: [] });
const money = (value: number) => Number.isFinite(value) ? value.toFixed(2) : '0.00';

export default function EditMenu() {
  const router = useRouter();
  const goBack = () => router.replace('/restaurant');
  const { user, ready } = useAuth();
  const insets = useSafeAreaInsets();
  const { height } = useWindowDimensions();
  const [menu, setMenu] = useState<RestaurantMenu | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState('');
  const [expanded, setExpanded] = useState<number | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const backendCover = useRef<string | null>(null);
  const backendPhotos = useRef(new Map<number, string | null>());

  const withLocalPhotos = useCallback((loaded: RestaurantMenu): RestaurantMenu => ({
    ...loaded,
    items: loaded.items.map((item) => {
      const localPhoto = item.id == null || !user ? undefined : readRestaurantItemPhoto(user.id, item.id);
      return localPhoto === undefined ? item : { ...item, photoUrl: localPhoto };
    }),
  }), [user?.id]);

  const createMenu = () => {
    const draft = emptyRestaurantMenu(user?.restaurantId ?? 0, user?.name ?? '');
    setMenu(draft);
    setMessage('');
  };

  useEffect(() => {
    if (!ready || user?.role !== 'restaurant') return;
    let active = true;
    readSessionToken().then((sessionToken) => {
      if (active) setToken(sessionToken);
      if (!sessionToken) throw new Error('Please sign in again');
      return loadRestaurantMenu(sessionToken);
    }).then((loaded) => {
      if (!loaded || !active) return;
      backendCover.current = loaded.coverUrl;
      backendPhotos.current = new Map(loaded.items.filter((item) => item.id != null).map((item) => [item.id!, item.photoUrl]));
      const localCover = readRestaurantCover(user.id);
      const withCover = localCover === undefined ? loaded : { ...loaded, coverUrl: localCover };
      setMenu(withLocalPhotos(withCover));
    }).catch((error) => { if (active) setMessage(error instanceof Error ? error.message : 'Could not load menu.'); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [ready, user?.role, user?.id, withLocalPhotos]);

  const update = (changes: Partial<RestaurantMenu>) => setMenu((current) => current && { ...current, ...changes });
  const updateItem = (index: number, changes: Partial<MenuItem>) => setMenu((current) => current && {
    ...current, items: current.items.map((item, position) => position === index ? { ...item, ...changes } : item),
  });
  const selectCover = (dataUri: string) => {
    if (!token || !user) return;
    setMessage('');
    try { writeRestaurantCover(user.id, dataUri); }
    catch { setMessage('Image selected for this session, but browser storage is full.'); }
    update({ coverUrl: dataUri });
  };
  const removeCover = () => {
    if (!token || !user) return;
    try { writeRestaurantCover(user.id, null); }
    catch { setMessage('Cover removed for this session, but browser storage is full.'); }
    update({ coverUrl: null });
  };
  const selectItemPhoto = (index: number, dataUri: string) => {
    if (!token || !user || !menu) return;
    setMessage('');
    const itemId = menu.items[index]?.id;
    if (itemId != null) {
      try { writeRestaurantItemPhoto(user.id, itemId, dataUri); }
      catch { setMessage('Image selected for this session, but browser storage is full.'); }
    }
    updateItem(index, { photoUrl: dataUri });
  };
  const removeItemPhoto = (index: number) => {
    if (!token || !user || !menu) return;
    const itemId = menu.items[index]?.id;
    if (itemId != null) {
      try { writeRestaurantItemPhoto(user.id, itemId, null); }
      catch { setMessage('Photo removed for this session, but browser storage is full.'); }
    }
    updateItem(index, { photoUrl: null });
  };
  const save = async () => {
    if (!menu || !token || saving) return;
    if (!menu.name.trim() || menu.items.some((item) => !item.name.trim() || !item.category.trim() || item.price < 0 || item.options.some((option) => !option.name.trim() || option.additionalCost < 0))) {
      setMessage('Complete each name, category, and nonnegative price before saving.'); return;
    }
    setSaving(true); setMessage('');
    try {
        const existingIds = new Set(menu.items.map((item) => item.id).filter((id): id is number => id != null));
        const saved = await saveRestaurantMenu(token, { ...menu, menuName: menu.menuName.trim() || 'Menu', coverUrl: backendCover.current, items: menu.items.map((item) => ({
          ...item,
          photoUrl: item.photoUrl?.startsWith('data:') ? (item.id == null ? null : backendPhotos.current.get(item.id) ?? null) : item.photoUrl,
        })) });
        backendCover.current = saved.coverUrl;
        backendPhotos.current = new Map(saved.items.filter((item) => item.id != null).map((item) => [item.id!, item.photoUrl]));
        const createdItems = saved.items.filter((item) => item.id != null && !existingIds.has(item.id));
        let createdIndex = 0;
        let photoStorageFull = false;
        menu.items.forEach((item) => {
          if (item.id != null) return;
          const created = createdItems[createdIndex++];
          if (created?.id == null || !item.photoUrl?.startsWith('data:') || !user) return;
          try { writeRestaurantItemPhoto(user.id, created.id, item.photoUrl); }
          catch { photoStorageFull = true; }
        });
        const localCover = user ? readRestaurantCover(user.id) : undefined;
        const withCover = localCover === undefined ? saved : { ...saved, coverUrl: localCover };
        setMenu(withLocalPhotos(withCover));
        setMessage(photoStorageFull ? 'Menu saved. A photo is available for this session, but browser storage is full.' : 'Menu saved. Picked images stay on this device.');
    }
    catch (error) { setMessage(error instanceof Error ? error.message : 'Could not save menu.'); }
    finally { setSaving(false); }
  };

  if (!ready) return <View style={local.center}><ActivityIndicator color="#D42F50" /></View>;
  if (!user) return <Redirect href="/auth/login" />;
  if (user.role !== 'restaurant') return <Redirect href="/account" />;
  if (loading) return <View style={local.center}><ActivityIndicator color="#D42F50" /></View>;
  if (!menu) return <View style={local.center}>
    <Text style={local.emptyTitle}>We couldn&apos;t fetch a menu for your restaurant.</Text>
    <Text style={local.emptyDetail}>{message}</Text>
    <Pressable accessibilityRole="button" style={local.addButton} onPress={createMenu}><Text style={local.addText}>Press here to create one</Text></Pressable>
    <Pressable accessibilityRole="button" onPress={goBack}><Text style={local.link}>Back</Text></Pressable>
  </View>;
  const coverHeight = Math.max(290, Math.min(height * 0.39, 390));
  const categories = [...new Set(menu.items.map((item) => item.category.trim() || 'Menu'))];
  return <DetailPage title={menu.name} cover={menu.coverUrl ? { uri: menu.coverUrl } : undefined} coverHeight={coverHeight} foreground={<><View style={[local.topBar, { top: insets.top + 12 }]}>
    <Pressable style={local.topButton} accessibilityRole="button" onPress={goBack}><Text style={local.topButtonText}>‹</Text></Pressable>
    <Pressable style={local.saveButton} accessibilityRole="button" accessibilityState={{ disabled: saving }} disabled={saving} onPress={save}><Text style={local.saveText}>{saving ? 'Saving…' : 'Save menu'}</Text></Pressable>
  </View><View style={{ position: 'absolute', top: coverHeight - 58, right: 18 }}><CoverImagePicker compact onSelect={selectCover} onError={setMessage} /></View></>}>
    <View style={customerStyles.info}>
      <Field label="Restaurant name" value={menu.name} placeholder="Your restaurant name" onChangeText={(name) => update({ name })} large />
      <View style={customerStyles.ratingLine}><Text style={customerStyles.muted}>Restaurant status</Text></View>
      <View style={local.chips}>{(['open', 'paused', 'closed'] as const).map((status) => <Chip key={status} label={status} selected={menu.status === status} onPress={() => update({ status })} />)}</View>
      <Field label="Address" value={menu.address ?? ''} placeholder="Street address" onChangeText={(address) => update({ address })} />
      <Text style={local.coverNote}>Cover images stay on this device.</Text>
      {!!menu.coverUrl && <Pressable accessibilityRole="button" onPress={removeCover}><Text style={local.link}>Remove cover image</Text></Pressable>}

    </View>
    <View style={customerStyles.menu}>
      <Text style={customerStyles.menuHeading}>Menu</Text>
      {menu.items.length === 0 && <Text style={local.emptyItems}>Your menu is empty. Add an item to get started.</Text>}
      {categories.map((category) => <View key={category} style={customerStyles.categorySection}>
        <Text style={customerStyles.categoryHeading}>{category}</Text>
        <View style={customerStyles.itemGrid}>{menu.items.map((item, index) => (item.category.trim() || 'Menu') === category && <Pressable key={item.id ?? `new-${index}`} style={customerStyles.menuCard} accessibilityRole="button" onPress={() => setExpanded(expanded === index ? null : index)}>
          <View style={customerStyles.itemImageWrap}>{item.photoUrl ? <Image source={{ uri: item.photoUrl }} style={customerStyles.itemImage} /> : <View style={customerStyles.itemImageFallback}><Text style={customerStyles.itemImageFallbackText}>Tender</Text></View>}</View>
          <View style={customerStyles.itemTitleRow}><Text style={customerStyles.itemName}>{item.name || 'New item'}</Text></View>
          {!!item.description && <Text style={customerStyles.itemDescription} numberOfLines={2}>{item.description}</Text>}
          {item.status === 'sold_out' && <Text style={customerStyles.itemAvailability}>Sold out</Text>}
          <Text style={customerStyles.itemPrice}>${money(item.price)}</Text>
          <Text style={local.editHint}>{expanded === index ? 'Close editor' : 'Edit item'}</Text>
        </Pressable>)}</View>
      </View>)}
      {expanded != null && menu.items[expanded] && <View style={local.editor}>
        <Text style={customerStyles.categoryHeading}>Edit item</Text>
        <Field label="Item name" value={menu.items[expanded].name} placeholder="e.g. Margherita pizza" onChangeText={(name) => updateItem(expanded, { name })} />
        <Field label="Description" value={menu.items[expanded].description ?? ''} placeholder="Describe this item" onChangeText={(description) => updateItem(expanded, { description })} multiline />
        <Field label="Category" value={menu.items[expanded].category} placeholder="e.g. Pizza" onChangeText={(category) => updateItem(expanded, { category })} />
        <Field label="Price ($)" value={menu.items[expanded].price ? String(menu.items[expanded].price) : ''} placeholder="0.00" onChangeText={(value) => updateItem(expanded, { price: Number(value) || 0 })} keyboardType="decimal-pad" />
        <CoverImagePicker label="Menu item photo" onSelect={(dataUri) => selectItemPhoto(expanded, dataUri)} onError={setMessage} />
        <Text style={local.coverNote}>Picked item photos stay on this device.</Text>
        {!!menu.items[expanded].photoUrl && <Pressable accessibilityRole="button" onPress={() => removeItemPhoto(expanded)}><Text style={local.link}>Remove item photo</Text></Pressable>}
        <Text style={local.label}>Availability</Text><View style={local.chips}><Chip label="Available" selected={menu.items[expanded].status === 'available'} onPress={() => updateItem(expanded, { status: 'available' })} /><Chip label="Sold out" selected={menu.items[expanded].status === 'sold_out'} onPress={() => updateItem(expanded, { status: 'sold_out' })} /></View>
        <Text style={local.subheading}>Toppings</Text>
        {menu.items[expanded].options.map((option, optionIndex) => <View key={option.id ?? `new-${optionIndex}`} style={local.optionRow}>
          <View style={local.optionName}><Field label={`Topping ${optionIndex + 1}`} value={option.name} onChangeText={(name) => updateItem(expanded, { options: menu.items[expanded].options.map((entry, position) => position === optionIndex ? { ...entry, name } : entry) })} /></View>
          <View style={local.optionPrice}><Field label="Extra ($)" value={String(option.additionalCost)} keyboardType="decimal-pad" onChangeText={(value) => updateItem(expanded, { options: menu.items[expanded].options.map((entry, position) => position === optionIndex ? { ...entry, additionalCost: Number(value) || 0 } : entry) })} /></View>
        </View>)}
        <Pressable onPress={() => updateItem(expanded, { options: [...menu.items[expanded].options, { id: null, name: '', additionalCost: 0 }] })}><Text style={local.link}>+ Add topping</Text></Pressable>
      </View>}
      <Pressable style={local.addButton} accessibilityRole="button" onPress={() => { update({ items: [...menu.items, newItem()] }); setExpanded(menu.items.length); }}><Text style={local.addText}>+ Add menu item</Text></Pressable>
      {!!message && <Text style={local.message}>{message}</Text>}
      <Pressable style={local.bottomSave} accessibilityRole="button" disabled={saving} onPress={save}><Text style={local.saveText}>{saving ? 'Saving…' : 'Save menu'}</Text></Pressable>
    </View>
  </DetailPage>;
}

function Field({ label, large, ...props }: { label: string; large?: boolean } & React.ComponentProps<typeof TextInput>) {
  return <View style={local.field}><Text style={local.label}>{label}</Text><TextInput {...props} style={[local.input, large && local.largeInput]} placeholderTextColor="#9F9092" /></View>;
}
function Chip({ label, selected, onPress }: { label: string; selected: boolean; onPress: () => void }) {
  return <Pressable accessibilityRole="button" accessibilityState={{ selected }} onPress={onPress} style={[local.chip, selected && local.selectedChip]}><Text style={[local.chipText, selected && local.selectedChipText]}>{label}</Text></Pressable>;
}
const local = StyleSheet.create({
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 14, paddingHorizontal: 24, backgroundColor: '#FFF' }, emptyTitle: { color: '#302629', fontSize: 22, fontWeight: '800', textAlign: 'center' }, emptyDetail: { color: '#887B7E', textAlign: 'center' }, emptyItems: { color: '#887B7E', marginTop: 20 }, coverNote: { color: '#887B7E', fontSize: 12, marginTop: 8 }, topBar: { position: 'absolute', left: 18, right: 18, flexDirection: 'row', justifyContent: 'space-between' }, topButton: { width: 42, height: 42, borderRadius: 21, backgroundColor: '#302629', alignItems: 'center', justifyContent: 'center' }, topButtonText: { color: '#FFF', fontSize: 30 }, saveButton: { backgroundColor: '#D42F50', paddingHorizontal: 18, justifyContent: 'center', borderRadius: 21 }, saveText: { color: '#FFF', fontWeight: '800' }, field: { marginTop: 15 }, label: { color: '#887B7E', fontSize: 11, fontWeight: '700', marginBottom: 6 }, input: { borderWidth: 1, borderColor: '#EDE6E6', borderRadius: 10, paddingHorizontal: 12, paddingVertical: 9, color: '#302629', fontSize: 14, backgroundColor: '#FFF' }, largeInput: { fontFamily: 'LoveLight', fontSize: 38, fontWeight: '400' }, chips: { flexDirection: 'row', gap: 8, marginTop: 8 }, chip: { borderRadius: 16, backgroundColor: '#F7F3F3', paddingHorizontal: 13, paddingVertical: 8 }, selectedChip: { backgroundColor: '#D42F50' }, chipText: { color: '#887B7E', fontSize: 12, fontWeight: '700', textTransform: 'capitalize' }, selectedChipText: { color: '#FFF' }, editHint: { color: '#D42F50', fontSize: 11, fontWeight: '700', marginTop: 7 }, editor: { backgroundColor: '#FFF8F9', borderRadius: 18, padding: 18, marginVertical: 20 }, subheading: { fontSize: 18, fontWeight: '800', color: '#302629', marginTop: 24 }, optionRow: { flexDirection: 'row', gap: 12 }, optionName: { flex: 2 }, optionPrice: { flex: 1 }, link: { color: '#D42F50', fontWeight: '800', marginTop: 15 }, addButton: { borderWidth: 1, borderColor: '#D42F50', borderRadius: 14, alignItems: 'center', padding: 14, marginTop: 15 }, addText: { color: '#D42F50', fontWeight: '800' }, message: { color: '#302629', marginTop: 16 }, bottomSave: { backgroundColor: '#D42F50', borderRadius: 14, alignItems: 'center', padding: 16, marginVertical: 20 },
});
