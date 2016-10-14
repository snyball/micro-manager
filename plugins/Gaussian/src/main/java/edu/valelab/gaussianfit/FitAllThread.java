/**
 * Implementation for the "Fit All" button
 * Fits all spots in the selected stack
 * 
 * Part of Micro-Manager's Localization Plugin
 * 
 * Nico Stuurman, copyright UCSF (2012)
 * 
 */

package edu.valelab.gaussianfit;

import com.google.common.eventbus.Subscribe;
import edu.valelab.gaussianfit.algorithm.FindLocalMaxima;
import edu.valelab.gaussianfit.utils.ProgressThread;
import edu.valelab.gaussianfit.data.GaussianInfo;
import edu.valelab.gaussianfit.data.SpotData;
import edu.valelab.gaussianfit.fitting.ZCalibrator;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import java.awt.Polygon;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.LinkedBlockingQueue;
import edu.valelab.gaussianfit.utils.ReportingUtils;
import java.util.List;
import org.micromanager.Studio;
import org.micromanager.data.Coords;
import org.micromanager.data.Coords.CoordsBuilder;
import org.micromanager.display.DisplayWindow;
import org.micromanager.display.PixelsSetEvent;

/**
 *
 * @author nico
 */
public class FitAllThread extends GaussianInfo implements Runnable  {
   double[] params0_;
   double[] steps_ = new double[5];
   GaussianFitStackThread[] gfsThreads_;
   private volatile Thread t_ = null;
   private static boolean running_ = false;
   private final FindLocalMaxima.FilterType preFilterType_;
   private final String positionString_;
   private boolean showDataWindow_ = true;
   private final Studio studio_;
   
   public class Monitor {}
   
   private final Monitor monitor_;
   private int desiredPos_;

   public FitAllThread(Studio studio, int shape, int fitMode, 
           FindLocalMaxima.FilterType preFilterType, String positions) {
      desiredPos_ = 0;
      monitor_ = new Monitor();
      studio_ = studio;
      shape_ = shape;
      fitMode_ = fitMode;
      preFilterType_ = preFilterType;
      positionString_ = positions;
   }

   
   public synchronized void  init() {
      if (running_)
         return;
      t_ = new Thread(this);
      running_ = true;
      t_.start();
   }
   
   public synchronized void join(long millis) throws InterruptedException {
      if (!running_) {
         return;
      }
      t_.join(millis);
   } 

   public synchronized void stop() {
      if (gfsThreads_ != null) {
         for (GaussianFitStackThread gfsThread : gfsThreads_) {
            if (gfsThread != null) {
               gfsThread.stop();
            }
         }
      }
      t_ = null;
      running_ = false;
   }

   public boolean isRunning() {
      return running_;
   }

   public synchronized List<SpotData> getResults() {
      return resultList_;
   }
   
   public synchronized void showDataWindow(boolean flag) {
      showDataWindow_ = flag;
   }
   
   @Override
   public void run() {

      // List with spot positions found through the Find Maxima command
      sourceList_ = new LinkedBlockingQueue<SpotData>();
      resultList_ = Collections.synchronizedList(new ArrayList<SpotData>());

      // take the active ImageJ image
      ImagePlus siPlus;
      try {
         siPlus = IJ.getImage();
      } catch (Exception ex) {
         stop();
         return;
      }
      
      DisplayWindow dw = studio_.displays().getCurrentWindow();

      int nrThreads = ij.Prefs.getThreads();
      if (nrThreads > 8) {
         nrThreads = 8;
      }

      Roi originalRoi = siPlus.getRoi();

      long startTime = System.nanoTime();
      int nrPositions = 1;
      int nrChannels = siPlus.getNChannels();
      int nrFrames = siPlus.getNFrames();
      int nrSlices = siPlus.getNSlices();
      // int maxNrSpots = 0;

      // If we have a Micro-Manager window:
      if (! (dw == null || siPlus != dw.getImagePlus()) ) {
         dw.registerForEvents(this);

         String[] parts = positionString_.split("-");
         nrPositions = dw.getDatastore().getAxisLength(Coords.STAGE_POSITION);
         int startPos = 1; int endPos = 1;
         if (parts.length > 0) {
            startPos = Integer.parseInt(parts[0]);
         }
         if (parts.length > 1) {
            endPos = Integer.parseInt(parts[1]);
         }
         if (endPos > nrPositions) {
            endPos = nrPositions;
         }
         if (endPos < startPos) {
            endPos = startPos;
         }

         CoordsBuilder builder = dw.getDisplayedImages().get(0).getCoords().copy();
         for (int p = startPos - 1; p <= endPos - 1; p++) {
            if (dw.getDisplayedImages().get(0).getCoords().getStagePosition() != p) {
               synchronized (monitor_) {
                  try {
                     desiredPos_ = p;
                     dw.setDisplayedImageTo(builder.stagePosition(p).build());
                     monitor_.wait();
                  } catch (InterruptedException ex) {
                     studio_.logs().logError("Display update thread got interrupted");
                  }
               }
            }

            siPlus.deleteRoi();
            //magePlus ip = siPlus.duplicate();

            analyzeImagePlus(siPlus, p + 1, nrThreads, originalRoi);
            //if (nrSpots > maxNrSpots) {
            //   maxNrSpots = nrSpots;
            //}
            siPlus.setRoi(originalRoi);
         }
         dw.unregisterForEvents(this);
      }

      if (dw == null || siPlus != dw.getImagePlus()) {
         analyzeImagePlus(siPlus, 1, nrThreads, originalRoi);
         //if (nrSpots > maxNrSpots) {
         //   maxNrSpots = nrSpots;
         //}
      }

      long endTime = System.nanoTime();

      // Add data to data overview window
      if (resultList_.size() < 1) {
         ReportingUtils.showError("No spots found");
         running_ = false;
         return;
      }
      
      DataCollectionForm dcForm = DataCollectionForm.getInstance();

      double zMax = resultList_.get(0).getZCenter();
      if (zMax < 0.0) {
         zMax = 0.0;
      }
      double zMin = zMax;
      ZCalibrator zc = DataCollectionForm.zc_;
      if (zc != null) {
         for (SpotData spot : resultList_) {
            double zTmp = spot.getZCenter();
            if (zMax < zTmp) {
               zMax = zTmp;
            }
            if (zMin > zTmp && zTmp > 0.0) {
               zMin = zTmp;
            }
         }
      }


      ArrayList<Double> timePoints = new ArrayList<Double>();
      // ugly code to deal with 1-based frame numbers and their relation to timePoints
      timePoints.add(0.0);
      for (int i = 1; i <= nrFrames; i++) {
         timePoints.add((i - 1) * timeIntervalMs_);
      }

      String title = siPlus.getTitle();
      if (nrPositions > 1) {
         title += "_Pos" + positionString_;
      }
      dcForm.addSpotData(title, siPlus.getTitle(), dw, "",
              siPlus.getWidth(), siPlus.getHeight(), pixelSize_,
              zStackStepSize_, shape_, halfSize_,
              nrChannels, nrFrames, nrSlices, nrPositions, resultList_.size(),
              resultList_, timePoints, false, DataCollectionForm.Coordinates.NM,
              DataCollectionForm.zc_.hasFitFunctions(),
              zMin, zMax);

      if (showDataWindow_) {
         dcForm.setVisible(true);
      }

      // report duration of analysis
      double took = (endTime - startTime) / 1E9;
      double rate = resultList_.size() / took;
      DecimalFormat df2 = new DecimalFormat("#.##");
      DecimalFormat df0 = new DecimalFormat("#");
      print("Analyzed " + resultList_.size() + " spots in " + df2.format(took)
              + " seconds (" + df0.format(rate) + " spots/sec.)");

      running_ = false;
   }

   @SuppressWarnings("unchecked")
   private int analyzeImagePlus(ImagePlus siPlus, int position, int nrThreads, Roi originalRoi) {

      int nrSpots = 0;
      // Start up IJ.Prefs.getThreads() threads for gaussian fitting
      gfsThreads_ = new GaussianFitStackThread[nrThreads];
      for (int i = 0; i < nrThreads; i++) {
         gfsThreads_[i] = new GaussianFitStackThread(sourceList_, resultList_, siPlus, halfSize_,
                 shape_, fitMode_);

         // TODO: more efficient way of passing through settings!
         gfsThreads_[i].setPhotonConversionFactor(photonConversionFactor_);
         gfsThreads_[i].setGain(gain_);
         gfsThreads_[i].setPixelSize(pixelSize_);
         gfsThreads_[i].setZStackStepSize(zStackStepSize_);
         gfsThreads_[i].setTimeIntervalMs(timeIntervalMs_);
         gfsThreads_[i].setBaseLevel(baseLevel_);
         gfsThreads_[i].setNoiseTolerance(noiseTolerance_);
         gfsThreads_[i].setSigmaMax(widthMax_);
         gfsThreads_[i].setSigmaMin(widthMin_);
         gfsThreads_[i].setNrPhotonsMin(nrPhotonsMin_);
         gfsThreads_[i].setNrPhotonsMax(nrPhotonsMax_);
         gfsThreads_[i].setMaxIterations(maxIterations_);
         gfsThreads_[i].setUseWidthFilter(useWidthFilter_);
         gfsThreads_[i].setUseNrPhotonsFilter(useNrPhotonsFilter_);
         gfsThreads_[i].setSkipChannels(skipChannels_);
         //gfsThreads_[i].setChannelsToSkip(channelsToSkip_);
         gfsThreads_[i].init();
      }
      int shownChannel = siPlus.getChannel();
      int shownSlice = siPlus.getSlice();
      int shownFrame = siPlus.getFrame();
      
      // work around strange bug that happens with freshly opened images
      for (int i = 1; i <= siPlus.getNChannels(); i++) {
         siPlus.setPosition(i, siPlus.getCurrentSlice(), siPlus.getFrame());
      }
      
      int nrChannels = siPlus.getNChannels();
      if (skipChannels_) {
         nrChannels -= channelsToSkip_.length;
      }
      int nrImages = nrChannels * siPlus.getNSlices() * siPlus.getNFrames();
      int imageCount = 0;
      try {
         for (int c = 1; c <= siPlus.getNChannels(); c++) {
            if (!running_) {
               break;
            }
            if (!skipChannels_ || !inArray(channelsToSkip_, c)) {
               for (int z = 1; z <= siPlus.getNSlices(); z++) {
                  if (!running_) {
                     break;
                  }
                  for (int f = 1; f <= siPlus.getNFrames(); f++) {
                     if (!running_) {
                        break;
                     }
                     // to avoid making a gigantic sourceList and running out of memory
                     // sleep a bit when the sourcesize gets too big
                     // once we have very fast multi-core computers, this constant can be increased
                     if (sourceList_.size() > 100000) {
                        try {
                           Thread.sleep(1000);
                        } catch (InterruptedException ex) {
                           // not sure what to do
                        }
                     }

                     imageCount++;
                     ij.IJ.showStatus("Processing image " + imageCount);

                     ImageProcessor siProc = null;
                     Polygon p = new Polygon();
                     synchronized (SpotData.lockIP) {
                        siPlus.setPositionWithoutUpdate(c, z, f);
                        // If ROI manager is used, use RoiManager Rois
                        //  may be dangerous if the user is not aware
                        RoiManager roiM = RoiManager.getInstance();
                        Roi[] rois = null;
                        //if (roiM != null && roiM.getSelectedIndex() > -1 ) {
                        //   rois = roiM.getSelectedRoisAsArray();
                        //}
                        if (rois != null && rois.length > 0) {
                           for (Roi roi : rois) {
                              siPlus.setRoi(roi, false);
                              siProc = siPlus.getProcessor();
                              Polygon q = FindLocalMaxima.FindMax(siPlus, halfSize_, noiseTolerance_,
                                      preFilterType_);
                              for (int i = 0; i < q.npoints; i++) {
                                 p.addPoint(q.xpoints[i], q.ypoints[i]);
                              }
                           }
                        } else {  // no Rois in RoiManager
                           siPlus.setRoi(originalRoi, false);
                           siProc = siPlus.getProcessor();
                           p = FindLocalMaxima.FindMax(siPlus, halfSize_, noiseTolerance_,
                                   preFilterType_);
                        }
                     }

                     ij.IJ.showProgress(imageCount, nrImages);

                     if (p.npoints > nrSpots) {
                        nrSpots = p.npoints;
                     }
                     int[][] sC = new int[p.npoints][2];
                     for (int j = 0; j < p.npoints; j++) {
                        sC[j][0] = p.xpoints[j];
                        sC[j][1] = p.ypoints[j];
                     }

                     Arrays.sort(sC, new SpotSortComparator());

                     for (int j = 0; j < sC.length; j++) {
                        // filter out spots too close to the edge
                        if (sC[j][0] > halfSize_ && sC[j][0] < siPlus.getWidth() - halfSize_
                                && sC[j][1] > halfSize_ && sC[j][1] < siPlus.getHeight() - halfSize_) {
                           ImageProcessor sp = SpotData.getSpotProcessor(siProc,
                                   halfSize_, sC[j][0], sC[j][1]);
                           if (sp == null) {
                              continue;
                           }
                           int channel = c;
                           /*
                           if (skipChannels_) {
                              // HACK we can only skip channel number 1
                              channel -= 1;
                           }
                           */
                           SpotData thisSpot = new SpotData(sp, channel, z, f,
                                   position, j, sC[j][0], sC[j][1]);
                           try {
                              sourceList_.put(thisSpot);
                           } catch (InterruptedException iex) {
                              Thread.currentThread().interrupt();
                              throw new RuntimeException("Unexpected interruption");
                           }
                        }
                     }
                  }
               }
            }
         }
         // start ProgresBar thread
         ProgressThread pt = new ProgressThread(sourceList_);
         pt.init();


      } catch (OutOfMemoryError ome) {
         ij.IJ.error("Out Of Memory");
      }

      // Send working threads signal that we are done:
      SpotData lastSpot = new SpotData(null, -1, 1, -1, -1, -1, -1, -1);
      try {
         sourceList_.put(lastSpot);
      } catch (InterruptedException iex) {
         Thread.currentThread().interrupt();
         throw new RuntimeException("Unexpected interruption");
      }

      // wait for worker threads to finish
      for (int i=0; i<nrThreads; i++) {
         try {
            gfsThreads_[i].join();
            gfsThreads_[i] = null;
         } catch (InterruptedException ie) {
         }
      }

      siPlus.setPosition(shownChannel, shownSlice, shownFrame);
       
      sourceList_.clear();
      return nrSpots;
   }
   
   @Subscribe
   public void checkDisplayedImage(PixelsSetEvent pse) {
      int pos = pse.getImage().getCoords().getStagePosition();
      
      synchronized(monitor_) {
         if (pos == desiredPos_) {
            monitor_.notify();
         } 
      }
   }
   
   
   
   

   private class SpotSortComparator implements Comparator {

      // Return the result of comparing the two row arrays
      @Override
      public int compare(Object o1, Object o2) {
         int[] p1 = (int[]) o1;
         int[] p2 = (int[]) o2;
         if (p1[0] < p2[0]) {
            return -1;
         }
         if (p1[0] > p2[0]) {
            return 1;
         }
         if (p1[0] == p2[0]) {
            if (p1[1] < p2[1]) {
               return -1;
            }
            if (p1[1] > p2[1]) {
               return 1;
            }
         }
         return 0;
      }
   }


   private static boolean inArray(int[] input, final int c) {
      for (final int n : input) {
         if (n == c) {
            return true;
         }
      }
      return false;
   }
   


}
